(ns org.clojuriststogether.app.cashflow-sync
  (:require [cheshire.core :as json]
            [clojure.java.jdbc :as jdbc]
            [clojure.string :as str]
            [clojure.tools.logging :as log]
            [integrant.core :as ig]
            [org.clojuriststogether.app.db]
            [org.clojuriststogether.app.stripe :as stripe])
  (:import (java.time Instant YearMonth ZoneOffset)
           (java.util.concurrent Executors ScheduledExecutorService TimeUnit ThreadFactory)))

(def event-types
  #{"customer.subscription.created" "customer.subscription.updated" "customer.subscription.deleted"
    "subscription_schedule.updated" "subscription_schedule.released" "subscription_schedule.canceled"
    "subscription_schedule.completed" "customer.updated"
    "charge.succeeded" "charge.updated" "charge.refunded" "invoice.paid"})

(defn enqueue! [db event]
  (when (event-types (:type event))
    (let [id (:id event) object-id (get-in event [:data :object :id])]
      (when-not (and (string? id) (not (str/blank? id))
                     (string? object-id) (not (str/blank? object-id)))
        (throw (ex-info "Invalid Stripe event identifiers" {})))
      (jdbc/execute! db ["INSERT INTO cashflow_stripe_events (id,event_type,object_id) VALUES (?,?,?) ON CONFLICT (id) DO NOTHING"
                         id (:type event) object-id]))))

(defn state [db]
  (first (jdbc/query db ["SELECT *, requested_start::text AS requested, history_start::text AS coverage FROM cashflow_stripe_snapshot WHERE id=1"])))

(defn history-start [start now]
  (let [fees (.minusMonths (YearMonth/from (.atZone ^Instant now ZoneOffset/UTC)) 12)]
    (if (.isBefore ^YearMonth start fees) start fees)))

(defn epoch [^YearMonth month]
  (.toEpochSecond (.atStartOfDay (.atDay month 1) ZoneOffset/UTC)))

(defn snapshot [db start]
  (let [now (Instant/now)
        required (str (.atDay (history-start start now) 1))
        {:keys [data coverage requested synced_at failed]} (state db)]
    (when (neg? (compare required requested))
      (jdbc/execute! db ["UPDATE cashflow_stripe_snapshot SET requested_start=LEAST(requested_start,?::date) WHERE id=1" required]))
    (when (or (nil? data) (pos? (compare coverage required)))
      (throw (ex-info (if failed "Stripe sync failed; it will retry automatically."
                          "Importing Stripe history. This page will refresh automatically.")
                      {:type ::stripe/loading})))
    (let [as-of (Instant/parse (:as-of data))
          since (epoch start)
          fee-since (epoch (.minusMonths (YearMonth/from (.atZone as-of ZoneOffset/UTC)) 12))]
      {:as-of as-of :subscriptions (:subscriptions data)
       :actuals (filterv #(>= (:created %) since) (:history data))
       :fee-history (filterv #(>= (:created %) fee-since) (:history data))
       :refresh-failed? failed
       :refreshing? (.isBefore ^Instant synced_at (.minusSeconds now 300))})))

(defn charges-since [since until]
  (stripe/forecast-list "charges" {"created[gte]" since "created[lt]" (.getEpochSecond ^Instant until)
                                   "expand[]" ["data.invoice" "data.balance_transaction" "data.customer"]}))

(defn merge-receipts [old charges]
  (vals
   (reduce (fn [receipts charge]
             (if-let [receipt (stripe/charge-receipt charge)]
               (assoc receipts (:id charge) (assoc receipt :charge-id (:id charge)))
               (dissoc receipts (:id charge))))
           (into {} (map (juxt :charge-id identity)) old) charges)))

(defn due? [{:keys [data requested coverage synced_at retry_at]} events now]
  (and (or (nil? retry_at) (not (.isBefore ^Instant now retry_at)))
       (or (nil? data) (seq events) (neg? (compare requested coverage))
           (.isBefore ^Instant synced_at (.minusSeconds ^Instant now 300)))))

(defn sync-data [current events]
  (let [began (Instant/now)
        full? (or (nil? (:data current))
                  (neg? (compare (:requested current) (:coverage current)))
                  (nil? (:reconciled_at current))
                  (.isBefore ^Instant (:reconciled_at current) (.minusSeconds began 86400)))
        subscriptions (stripe/subscriptions)
        as-of (Instant/now)
        start (YearMonth/parse (subs (:requested current) 0 7))
        since (if full? (epoch start) (max (epoch start) (- (.getEpochSecond ^Instant (:synced_at current)) 604800)))
        charges (charges-since since as-of)
        listed (set (map :id charges))
        changed (distinct (for [{:keys [event_type object_id]} events
                                :when (and (str/starts-with? event_type "charge.") (not (listed object_id)))] object_id))
        charges (into charges (map #(stripe/forecast-request (str "charges/" %)
                                                             {"expand[]" ["invoice" "balance_transaction" "customer"]}) changed))
        history (vec (merge-receipts (when-not full? (get-in current [:data :history])) charges))]
    {:data {:as-of (str as-of) :subscriptions subscriptions :history history}
     :as-of as-of :coverage (if full? (:requested current) (:coverage current))
     :reconciled-at (if full? as-of (:reconciled_at current))}))

(defn sync-once! [db]
  ;; A session lock prevents overlapping workers across deploys/dynos; publication
  ;; uses a short transaction only after all Stripe reads have completed.
  (jdbc/with-db-connection [conn db]
    (when (:locked (first (jdbc/query conn ["SELECT pg_try_advisory_lock(73618291) AS locked"])))
      (try
        (let [current (state conn)
              events (jdbc/query conn ["SELECT id,event_type,object_id FROM cashflow_stripe_events WHERE processed_at IS NULL ORDER BY received_at LIMIT 500"])]
          (when (due? current events (Instant/now))
            (let [began (System/nanoTime)
                  {:keys [data as-of coverage reconciled-at]} (sync-data current events)]
              (jdbc/with-db-transaction [tx conn]
                (jdbc/execute! tx ["UPDATE cashflow_stripe_snapshot SET data=?::jsonb, history_start=?::date, synced_at=?, reconciled_at=?, retry_at=NULL, failed=FALSE WHERE id=1"
                                   (json/generate-string data) coverage as-of reconciled-at])
                (doseq [event events]
                  (jdbc/execute! tx ["UPDATE cashflow_stripe_events SET processed_at=now() WHERE id=?" (:id event)])))
              (log/info "Cashflow Stripe sync complete"
                        {:milliseconds (quot (- (System/nanoTime) began) 1000000)
                         :subscriptions (count (:subscriptions data)) :receipts (count (:history data))
                         :events (count events)}))))
        (catch Exception e
          (jdbc/execute! conn ["UPDATE cashflow_stripe_snapshot SET failed=TRUE,retry_at=now()+interval '60 seconds' WHERE id=1"])
          (log/warn "Cashflow Stripe sync failed; retaining previous data and retrying in 60 seconds"
                    {:class (.getName (class e)) :type (:type (ex-data e))}))
        (finally (jdbc/query conn ["SELECT pg_advisory_unlock(73618291)"]))))))

(defmethod ig/init-key ::worker [_ {:keys [db enabled]}]
  (when enabled
    (let [executor (Executors/newSingleThreadScheduledExecutor
                    (reify ThreadFactory
                      (newThread [_ runnable] (doto (Thread. runnable "cashflow-stripe-sync") (.setDaemon true)))))]
      (.scheduleWithFixedDelay executor
                               ^Runnable (fn [] (try (sync-once! db)
                                                     (catch Exception _ (log/warn "Cashflow sync worker could not reach database"))))
                               0 30 TimeUnit/SECONDS)
      executor)))

(defmethod ig/halt-key! ::worker [_ executor]
  (when executor (.shutdownNow ^ScheduledExecutorService executor)))
