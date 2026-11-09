(ns org.clojuriststogether.app.stripe
  (:require [integrant.core :as ig]
            [cheshire.core :as json])
  (:import (com.stripe Stripe)
           (com.stripe.model Subscription SubscriptionItem Charge)
           (java.time Instant YearMonth ZoneOffset)))

(defn customer-id [customer]
  (if (map? customer) (:id customer) customer))

(defn customer-name [customer]
  (when (map? customer) (or (not-empty (:name customer)) (not-empty (:email customer)))))

(defn subscriptions []
  ;; The SDK pins the API version and auto-pages both subscriptions and items.
  (mapv (fn [^Subscription subscription]
          (let [s (json/parse-string (.toJson subscription) true)]
          (assoc-in (assoc s :customer (customer-id (:customer s))
                            :customer-name (customer-name (:customer s))) [:items :data]
                    (mapv #(json/parse-string (.toJson %) true)
                          (if (.getHasMore (.getItems subscription))
                            (.autoPagingIterable
                              (SubscriptionItem/list
                                {"subscription" (.getId subscription) "limit" 100}))
                            (.getData (.getItems subscription)))))))
        (remove #(#{"canceled" "incomplete_expired"} (.getStatus ^Subscription %))
                (.autoPagingIterable (Subscription/list {"status" "all" "limit" 100 "expand" ["data.customer" "data.latest_invoice"]})))))

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
      (into [] (keep #(charge-receipt (json/parse-string (.toJson %) true)))
            (.autoPagingIterable
              (Charge/list {"created" {"gte" since "lt" (.getEpochSecond now)}
                            "limit" 100
                            "expand" ["data.invoice" "data.balance_transaction" "data.customer"]}))))))

(defonce ^:private snapshot-cache (atom nil))

(defn snapshot [start]
  ;; Keep one coherent snapshot for five minutes, including its cut-off time.
  ;; Scenario changes and Excel exports can reuse it without refetching history.
  (locking snapshot-cache
    (let [now (Instant/now)
          cache-key [start Stripe/apiKey]
          cached @snapshot-cache]
      (if (and (= cache-key (:key cached))
               (.isBefore now (:expires-at cached)))
        (:data cached)
        (let [subs (subscriptions)
              ;; Capture the cut-off after reading renewal dates, before loading
              ;; history, so the long history request doesn't advance subscriptions
              ;; beyond the actual payments included in this snapshot.
              as-of (Instant/now)
              fee-start (.minusMonths (YearMonth/from (.atZone as-of ZoneOffset/UTC)) 12)
              history-start (if (.isBefore ^YearMonth start fee-start) start fee-start)
              history (actual-receipts history-start as-of)
              report-since (.toEpochSecond (.atStartOfDay (.atDay ^YearMonth start 1) ZoneOffset/UTC))
              fee-since (.toEpochSecond (.atStartOfDay (.atDay fee-start 1) ZoneOffset/UTC))
              data {:as-of as-of :subscriptions subs
                    :actuals (filterv #(>= (:created %) report-since) history)
                    :fee-history (filterv #(>= (:created %) fee-since) history)}]
          (reset! snapshot-cache {:key cache-key :data data :expires-at (.plusSeconds (Instant/now) 300)})
          data)))))

(defmethod ig/init-key ::stripe
  [_ {:keys [publishable-key secret-key]}]

  (set! Stripe/apiKey secret-key)
  {:publishable-key publishable-key})
