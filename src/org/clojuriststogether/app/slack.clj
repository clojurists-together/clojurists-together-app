(ns org.clojuriststogether.app.slack
  (:require [cheshire.core :as json]
            [clj-http.client :as http]
            [clojure.java.jdbc :as jdbc]
            [clojure.string :as str]
            [clojure.tools.logging :as log]
            [integrant.core :as ig]
            [org.clojuriststogether.app.db]
            [org.clojuriststogether.app.stripe :as stripe])
  (:import (java.time Instant ZoneOffset)
           (java.time.format DateTimeFormatter)
           (java.util.concurrent Executors ScheduledExecutorService TimeUnit ThreadFactory)))

(def event-types
  #{"customer.created" "customer.subscription.created" "customer.subscription.updated" "customer.subscription.deleted"})

(defn- cancellation-scheduled? [subscription]
  (or (true? (:cancel_at_period_end subscription)) (some? (:cancel_at subscription))))

(defn notification-kind [{:keys [type data]}]
  (let [subscription (:object data)
        previous (:previous_attributes data)
        cancellation-changed? (some #(and (contains? previous %) (not= (get previous %) (get subscription %)))
                                    [:cancel_at_period_end :cancel_at])]
    (case type
      "customer.created" :customer-created
      "customer.subscription.created" :created
      "customer.subscription.deleted" :cancelled
      "customer.subscription.updated"
      (cond
        cancellation-changed? (if (cancellation-scheduled? subscription) :cancellation-scheduled :cancellation-reversed)
        (some #(and (contains? previous %) (not= (get previous %) (get subscription %)))
              [:items :plan :quantity :status :discount :discounts :pause_collection]) :updated)
      nil)))

(defn enqueue! [db event]
  (when (notification-kind event)
    (when-not (and (string? (:id event)) (re-matches #"evt_[A-Za-z0-9]+" (:id event)))
      (throw (ex-info "Invalid Stripe notification event ID" {})))
    (jdbc/execute! db ["INSERT INTO stripe_slack_notifications (event_id,payload) VALUES (?,?::jsonb) ON CONFLICT (event_id) DO NOTHING"
                       (:id event) (json/generate-string (select-keys event [:id :type :created :livemode :data]))])))

(defn- object-id [object]
  (if (map? object) (:id object) object))

(defn- escape-text [value]
  (-> (str value) (str/replace "&" "&amp;") (str/replace "<" "&lt;") (str/replace ">" "&gt;")))

(defn- display-text [value]
  {:type "plain_text" :text (subs (str value) 0 (min 2000 (count (str value))))})

(def zero-decimal-currencies
  #{"bif" "clp" "djf" "gnf" "jpy" "kmf" "krw" "mga" "pyg" "rwf" "ugx" "vnd" "vuv" "xaf" "xof" "xpf"})

(defn- amount-label [price quantity]
  (let [currency (:currency price)
        amount (or (:unit_amount_decimal price) (:unit_amount price) (:amount price))
        digits (cond (zero-decimal-currencies currency) 0
                     (#{"bhd" "jod" "kwd" "omr" "tnd"} currency) 3
                     :else 2)
        recurring (:recurring price)
        interval (or (:interval recurring) (:interval price))
        n (or (:interval_count recurring) (:interval_count price) 1)]
    (str (if amount
           (str (str/upper-case currency) " " (.toPlainString (.stripTrailingZeros (.movePointLeft (* (bigdec amount) (bigdec quantity)) digits))))
           "Usage-based or tiered amount")
         (when interval (if (= n 1) (str " per " interval) (str " every " n " " interval "s")))
         (when (not= quantity 1) (str " (quantity " quantity ")")))))

(defn- item-details [item]
  (let [snapshot (or (:price item) (:plan item))
        price (if (map? snapshot) snapshot (stripe/notification-object :price snapshot))
        product (:product price)
        product (if (map? product) product (when product (stripe/notification-object :product product)))
        plan (or (not-empty (:nickname price)) (not-empty (:lookup_key price)) (:id price))]
    (str (or (:name product) "Unknown product") " — " plan " — " (amount-label price (or (:quantity item) 1)))))

(defn message
  "Formats the event snapshot, enriching names without reading current subscription state."
  [db event]
  (let [subscription (get-in event [:data :object])
        new-customer? (= "customer.created" (:type event))
        customer (if new-customer? subscription (:customer subscription))
        customer-id (object-id customer)
        customer (if (map? customer) customer (stripe/notification-object :customer customer-id))
        member (first (jdbc/query db ["SELECT person_name,organization_name,email FROM members WHERE stripe_customer_id=? ORDER BY id LIMIT 1" customer-id]))
        name (or (not-empty (:name customer)) (not-empty (:organization_name member))
                 (not-empty (:person_name member)) (:email customer) (:email member) customer-id)
        email (or (:email customer) (:email member))
        kind (notification-kind event)
        title (case kind :customer-created "New customer" :created "New subscription" :updated "Subscription updated" :cancelled "Subscription cancelled"
                    :cancellation-scheduled "Subscription cancellation scheduled"
                    :cancellation-reversed "Subscription cancellation reversed")
        items (or (seq (get-in subscription [:items :data]))
                  (when (:plan subscription) [{:plan (:plan subscription) :quantity (:quantity subscription)}]))
        _ (when (get-in subscription [:items :has_more])
            (throw (ex-info "Subscription notification has truncated items" {})))
        details (mapv item-details items)
        end (or (:cancel_at subscription) (:current_period_end subscription)
                (when-let [ends (seq (keep :current_period_end items))] (apply max ends)))
        cancellation (case kind
                       :cancellation-scheduled (str "Cancellation requested; remains active until "
                                                    (if end (.format (DateTimeFormatter/ofPattern "yyyy-MM-dd HH:mm 'UTC'") (.atOffset (Instant/ofEpochSecond end) ZoneOffset/UTC))
                                                        "the end of the billing period") ".")
                       :cancellation-reversed "Scheduled cancellation removed; subscription will continue."
                       :cancelled "Subscription has ended."
                       nil)
        customer-label (str name (when (and email (not= name email)) (str " (" email ")")))
        text (str title "\n" customer-label "\n" (str/join "\n" details)
                  (when cancellation (str "\n" cancellation)))
        dashboard (str "https://dashboard.stripe.com/" (when (false? (:livemode event)) "test/") (if new-customer? "customers/" "subscriptions/") (:id subscription))]
    {:text text
     :mrkdwn false
     :unfurl_links false
     :unfurl_media false
     :blocks (vec (concat [{:type "header" :text (display-text title)}
                           {:type "section" :text (display-text customer-label)}]
                          (for [detail details] {:type "section" :text (display-text detail)})
                          (when cancellation [{:type "section" :text (display-text cancellation)}])
                          [{:type "context" :elements [{:type "mrkdwn" :text (str "<" dashboard (if new-customer? "|View customer> · " "|View subscription> · ") (escape-text (:id event)))}]}]))}))

(defn send-message! [url body]
  (let [response (http/post url {:body (json/generate-string body) :content-type :json
                                 :throw-exceptions false :follow-redirects false
                                 :conn-timeout 5000 :socket-timeout 10000})]
    (when-not (and (= 200 (:status response)) (= "ok" (str/trim (:body response))))
      (throw (ex-info "Slack rejected notification" {:status (:status response)})))))

(defn deliver-once! [db url]
  (jdbc/with-db-connection [conn db]
    (when (:locked (first (jdbc/query conn ["SELECT pg_try_advisory_lock(73618292) AS locked"])))
      (try
        (doseq [{:keys [event_id payload message]} (jdbc/query conn ["SELECT event_id,payload,message FROM stripe_slack_notifications WHERE delivered_at IS NULL AND retry_at<=now() ORDER BY received_at LIMIT 20"])]
          (try
            (let [body (or message (org.clojuriststogether.app.slack/message conn payload))]
              (when-not message
                (jdbc/execute! conn ["UPDATE stripe_slack_notifications SET message=?::jsonb WHERE event_id=?" (json/generate-string body) event_id]))
              (send-message! url body)
              (jdbc/execute! conn ["UPDATE stripe_slack_notifications SET delivered_at=now(),attempts=attempts+1,last_error=NULL WHERE event_id=?" event_id]))
            (catch Exception e
              (jdbc/execute! conn ["UPDATE stripe_slack_notifications SET attempts=attempts+1,retry_at=now()+interval '60 seconds',last_error=? WHERE event_id=?"
                                   (str (.getSimpleName (class e)) (when-let [status (:status (ex-data e))] (str " HTTP " status))) event_id])
              (log/warn "Slack subscription notification will retry" {:event-id event_id}))))
        (finally (jdbc/query conn ["SELECT pg_advisory_unlock(73618292)"]))))))

(defmethod ig/init-key ::worker [_ {:keys [db webhook-url]}]
  (when-not (str/blank? webhook-url)
    (when-not (re-matches #"https://hooks\.slack\.com/services/[^/]+/[^/]+/[^/?#]+" webhook-url)
      (throw (ex-info "Invalid Slack incoming webhook URL" {})))
    (let [executor (Executors/newSingleThreadScheduledExecutor
                    (reify ThreadFactory
                      (newThread [_ runnable] (doto (Thread. runnable "stripe-slack-notifications") (.setDaemon true)))))]
      (.scheduleWithFixedDelay executor ^Runnable (fn [] (try (deliver-once! db webhook-url)
                                                              (catch Exception _ (log/warn "Slack worker could not reach database"))))
                               0 5 TimeUnit/SECONDS)
      executor)))

(defmethod ig/halt-key! ::worker [_ executor]
  (when executor (.shutdownNow ^ScheduledExecutorService executor)))
