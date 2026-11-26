(ns org.clojuriststogether.app.cashflow-sync-test
  (:require [clojure.test :refer :all]
            [clojure.java.jdbc :as jdbc]
            [clojure.java.io :as io]
            [org.clojuriststogether.app.cashflow-sync :as sync]
            [org.clojuriststogether.app.pages.cashflow :as page]
            [org.clojuriststogether.app.stripe :as stripe])
  (:import (java.time Instant YearMonth ZoneOffset)
           (java.util UUID)))

(defn charge [id created fee]
  {:id id :paid true :status "succeeded" :created created :currency "usd"
   :customer {:id "cus_1" :name "Company"} :invoice {:subscription "sub_1"}
   :balance_transaction {:currency "usd" :amount 1000 :net (- 1000 fee) :fee fee}})

(deftest receipt-upserts-do-not-double-count-and-fail-on-pending-fees
  (let [c (charge "ch_1" 123 30)
        old (sync/merge-receipts [] [c c])]
    (is (= 1 (count old)))
    (is (= 950 (:net (first (sync/merge-receipts old [(charge "ch_1" 123 50)])))))
    (is (empty? (sync/merge-receipts old [(assoc c :paid false)])))
    (is (thrown? clojure.lang.ExceptionInfo
                 (sync/merge-receipts old [(assoc c :balance_transaction nil)])))))

(deftest loading-response-does-not-export-partial-data
  (with-redefs [sync/snapshot (fn [& _] (throw (ex-info "Importing" {:type ::stripe/loading})))]
    (is (= 202 (:status ((page/handler {} false) {:query-params {}}))))
    (let [export ((page/handler {} true) {:query-params {}})]
      (is (= 503 (:status export)))
      (is (nil? (get-in export [:headers "Content-Disposition"]))))))

(deftest persistent-sync-integration
  (if-let [url (System/getenv "CASHFLOW_SYNC_TEST_DATABASE_URL")]
    (jdbc/with-db-connection [db {:connection-uri url}]
      (let [schema (str "cashflow_test_" (str (.replace (str (UUID/randomUUID)) "-" "")))
            now (Instant/now)
            start (YearMonth/from (.atZone now ZoneOffset/UTC))
            payment (charge "ch_1" (- (.getEpochSecond now) 100) 30)
            event {:id "evt_1" :type "charge.updated" :data {:object {:id "ch_1"}}}]
        (jdbc/db-do-commands db (str "CREATE SCHEMA " schema))
        (try
          (jdbc/db-do-commands db (str "SET search_path TO " schema))
          (doseq [sql (clojure.string/split (slurp (io/resource "migrations/005-cashflow-sync.up.sql")) #";")
                  :when (not (clojure.string/blank? sql))]
            (jdbc/db-do-commands db sql))
          (is (thrown? clojure.lang.ExceptionInfo (sync/snapshot db start)))
          (with-redefs [stripe/subscriptions (constantly [{:id "sub_1"}])
                        sync/charges-since (fn [& _] [payment])]
            (sync/sync-once! db))
          (is (= 970 (:net (first (:actuals (sync/snapshot db start))))))
          (is (false? (:failed (sync/state db))))
          ;; Repeated and old webhook deliveries fetch current Stripe state.
          (sync/enqueue! db event)
          (sync/enqueue! db event)
          (is (= 1 (:n (first (jdbc/query db ["SELECT count(*) AS n FROM cashflow_stripe_events"])))))
          (with-redefs [stripe/subscriptions (constantly [{:id "sub_1" :status "canceled"}])
                        sync/charges-since (fn [& _] [])
                        stripe/forecast-request (fn [& _] (charge "ch_1" (:created payment) 50))]
            (sync/sync-once! db))
          (is (= 950 (:net (first (:actuals (sync/snapshot db start))))))
          (is (some? (:processed_at (first (jdbc/query db ["SELECT processed_at FROM cashflow_stripe_events"])))))
          ;; Failure preserves the complete snapshot and leaves durable work pending.
          (sync/enqueue! db (assoc event :id "evt_2"))
          (let [before (:data (sync/state db))]
            (with-redefs [stripe/subscriptions (fn [] (throw (ex-info "network failure" {})))]
              (sync/sync-once! db))
            (is (= before (:data (sync/state db))))
            (is (:refresh-failed? (sync/snapshot db start)))
            (is (= 1 (:n (first (jdbc/query db ["SELECT count(*) AS n FROM cashflow_stripe_events WHERE processed_at IS NULL"])))))
            (is (not (sync/due? (sync/state db) [event] (Instant/now)))))
          ;; Work and history requests arriving during a sync are not lost when it publishes.
          (jdbc/execute! db ["UPDATE cashflow_stripe_snapshot SET retry_at=NULL"])
          (let [earlier (.minusYears start 3)]
            (with-redefs [stripe/subscriptions (constantly [{:id "sub_1"}])
                          sync/charges-since (fn [& _]
                                               (sync/enqueue! db (assoc event :id "evt_3"))
                                               (is (thrown? clojure.lang.ExceptionInfo (sync/snapshot db earlier)))
                                               [(charge "ch_1" (:created payment) 50)])]
              (sync/sync-once! db))
            (is (nil? (:processed_at (first (jdbc/query db ["SELECT processed_at FROM cashflow_stripe_events WHERE id='evt_3'"])))))
            (is (= (str (.atDay earlier 1)) (:requested (sync/state db))))
            (is (sync/due? (sync/state db) [] (Instant/now))))
          ;; A new connection (like a restarted dyno) sees the saved snapshot.
          (jdbc/with-db-connection [other {:connection-uri url}]
            (jdbc/db-do-commands other (str "SET search_path TO " schema))
            (is (= 950 (:net (first (:actuals (sync/snapshot other start))))))
            (jdbc/execute! db ["UPDATE cashflow_stripe_snapshot SET retry_at=NULL"])
            (jdbc/query db ["SELECT pg_advisory_lock(73618291)"])
            (try
              (with-redefs [sync/sync-data (fn [& _] (throw (AssertionError. "Concurrent worker ran")))]
                (sync/sync-once! other))
              (finally (jdbc/query db ["SELECT pg_advisory_unlock(73618291)"]))))
          (finally (jdbc/db-do-commands db (str "DROP SCHEMA " schema " CASCADE"))))))
    (println "Postgres integration test skipped: set CASHFLOW_SYNC_TEST_DATABASE_URL to run.")))

(deftest daily-reconciliation-replaces-history-and-incremental-sync-retains-it
  (let [now (Instant/now)
        month (.minusMonths (YearMonth/from (.atZone now ZoneOffset/UTC)) 12)
        coverage (str (.atDay month 1))
        old-receipt (assoc (stripe/charge-receipt (charge "ch_old" (sync/epoch month) 30)) :charge-id "ch_old")
        current {:requested coverage :coverage coverage :synced_at now :reconciled_at now
                 :data {:history [old-receipt]}}
        ranges (atom [])]
    (with-redefs [stripe/subscriptions (constantly [])
                  sync/charges-since (fn [since _] (swap! ranges conj since) [])]
      (is (= [old-receipt] (get-in (sync/sync-data current []) [:data :history])))
      (is (= (- (.getEpochSecond now) 604800) (first @ranges)))
      (is (empty? (get-in (sync/sync-data (assoc current :reconciled_at (.minusSeconds now 86401)) []) [:data :history])))
      (is (= (sync/epoch month) (last @ranges))))))
