(ns org.clojuriststogether.app.stripe
  (:require [integrant.core :as ig]
            [cheshire.core :as json]
            [clj-http.client :as http]
            [clojure.tools.logging :as log])
  (:import (com.stripe Stripe)
           (com.stripe.model Customer)
           (com.stripe.net RequestOptions)
           (java.time Instant YearMonth ZoneOffset)))

(defn customer-id [customer]
  (if (map? customer) (:id customer) customer))

(defn customer-name [customer]
  (when (map? customer) (or (not-empty (:name customer)) (not-empty (:email customer)))))

(defn forecast-request [path params]
  (try
    (let [response (http/get (str "https://api.stripe.com/v1/" path)
                             {:headers {"Authorization" (str "Bearer " Stripe/apiKey)
                                        "Stripe-Version" "2020-08-27"}
                              :query-params params :throw-exceptions false
                              :conn-timeout 15000 :socket-timeout 30000})]
      (when-not (= 200 (:status response))
        (throw (ex-info "Stripe could not be loaded. Please retry." {:type ::unavailable})))
      (json/parse-string (:body response) true))
    (catch Exception _
      (throw (ex-info "Stripe could not be loaded. Please retry." {:type ::unavailable})))))

(defn forecast-list [path params]
  (loop [params (assoc params "limit" 100) result []]
    (let [{:keys [data has_more]} (forecast-request path params)]
      (if (and has_more (seq data))
        (recur (assoc params "starting_after" (:id (last data))) (into result data))
        (into result data)))))

(defn subscriptions []
  (mapv (fn [s]
          (assoc-in (assoc s :customer (customer-id (:customer s))
                           :customer-name (customer-name (:customer s))) [:items :data]
                    (if (get-in s [:items :has_more])
                      (forecast-list "subscription_items" {"subscription" (:id s)})
                      (get-in s [:items :data]))))
        (remove #(#{"canceled" "incomplete_expired"} (:status %))
                (forecast-list "subscriptions" {"expand[]" ["data.customer" "data.latest_invoice" "data.schedule"]}))))

(defn charge-receipt [charge]
  ;; Include receipts from subscriptions that have since been cancelled, and from
  ;; manual invoices actually paid through Stripe. Never estimate historical fees.
  (when (and (:paid charge) (= "succeeded" (:status charge)) (get-in charge [:invoice :subscription]))
    (let [balance (:balance_transaction charge)]
      (when-not (and (map? balance) (number? (:net balance)))
        (throw (ex-info "A subscription payment has no recorded Stripe fee yet. Please retry later."
                        {:type ::actuals-unavailable})))
      {:customer (customer-id (:customer charge))
       :customer-name (customer-name (:customer charge))
       :subscription (get-in charge [:invoice :subscription])
       :created (:created charge) :currency (:currency balance) :net (:net balance)
       :gross (:amount balance) :fee (:fee balance) :charge-currency (:currency charge)})))

(defn actual-receipts [^YearMonth start ^Instant now]
  (let [since (.toEpochSecond (.atStartOfDay (.atDay start 1) ZoneOffset/UTC))]
    (if (>= since (.getEpochSecond now)) []
        (into [] (keep charge-receipt)
              (forecast-list "charges" {"created[gte]" since "created[lt]" (.getEpochSecond now)
                                        "expand[]" ["data.invoice" "data.balance_transaction" "data.customer"]})))))

(defonce ^:private snapshot-cache (atom nil))

(defn load-snapshot [start]
  (let [began (System/nanoTime)
        subs (subscriptions)
        ;; Keep the history cut-off after renewal retrieval, as before.
        as-of (Instant/now)
        fee-start (.minusMonths (YearMonth/from (.atZone as-of ZoneOffset/UTC)) 12)
        history-start (if (.isBefore ^YearMonth start fee-start) start fee-start)
        history (actual-receipts history-start as-of)
        report-since (.toEpochSecond (.atStartOfDay (.atDay ^YearMonth start 1) ZoneOffset/UTC))
        fee-since (.toEpochSecond (.atStartOfDay (.atDay fee-start 1) ZoneOffset/UTC))]
    (log/info "Cashflow Stripe snapshot loaded"
              {:milliseconds (quot (- (System/nanoTime) began) 1000000)
               :subscriptions (count subs) :receipts (count history)})
    {:as-of as-of :subscriptions subs
     :actuals (filterv #(>= (:created %) report-since) history)
     :fee-history (filterv #(>= (:created %) fee-since) history)}))

(defn cached-snapshot
  "One background refresh per dyno. Never hold the cache lock during Stripe I/O."
  [cache cache-key load-data now]
  (locking cache
    (let [cached @cache
          matches? (= cache-key (:key cached))
          fresh? (and matches? (:expires-at cached) (.isBefore now (:expires-at cached)))
          retry? (or (not matches?) (nil? (:retry-at cached))
                     (not (.isBefore now (:retry-at cached))))]
      (when (and (not fresh?) (not (:loading? cached)) retry?)
        (reset! cache (assoc (when matches? cached) :key cache-key :loading? true :error nil))
        (future
          (try
            (let [data (load-data)
                  finished (Instant/now)]
              (reset! cache {:key cache-key :data data
                             :expires-at (.plusSeconds finished 300)
                             :usable-until (.plusSeconds finished 86400)}))
            (catch Exception _
              ;; Do not log Stripe responses or credentials.
              (log/warn "Cashflow Stripe refresh failed; retry available in 30 seconds")
              (swap! cache assoc :loading? false :error true
                     :retry-at (.plusSeconds (Instant/now) 30))))))
      (let [current @cache]
        (if (and (= cache-key (:key current)) (:data current)
                 (.isBefore now (:usable-until current)))
          (assoc (:data current) :refreshing? (boolean (:loading? current))
                 :refresh-failed? (boolean (:error current)))
          (throw (ex-info (if (:error current)
                            "Stripe refresh failed. Retrying shortly."
                            "Loading Stripe subscriptions and payment history. This page will refresh automatically.")
                          {:type ::loading})))))))

(defn snapshot [start]
  (cached-snapshot snapshot-cache [start Stripe/apiKey]
                   #(load-snapshot start) (Instant/now)))

(defmethod ig/init-key ::stripe
  [_ {:keys [secret-key]}]
  (set! Stripe/apiKey secret-key)
  nil)

(defn- request-options [api-key]
  (-> (RequestOptions/builder) (.setApiKey api-key) .build))

(defn customers
  "Every customer's id and email, using the given key rather than the app's own."
  [api-key]
  (let [^java.util.Map params {"limit" 100}]
    (->> (.autoPagingIterable (Customer/list params ^RequestOptions (request-options api-key)))
         (mapv (fn [^Customer customer]
                 {:id (.getId customer)
                  :email (.getEmail customer)})))))

(defn set-customer-email! [api-key customer-id email]
  (let [^RequestOptions options (request-options api-key)
        ^java.util.Map params {"email" email}
        ^Customer customer (Customer/retrieve ^String customer-id options)]
    (.update customer params options)))
