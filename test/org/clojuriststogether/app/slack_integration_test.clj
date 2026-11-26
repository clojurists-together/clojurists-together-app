(ns org.clojuriststogether.app.slack-integration-test
  (:require [cheshire.core :as json]
            [clj-http.client :as http]
            [clojure.java.io :as io]
            [clojure.java.jdbc :as jdbc]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [integrant.core :as ig]
            [ring.adapter.jetty :as jetty]
            [org.clojuriststogether.app.routes]
            [org.clojuriststogether.app.slack :as slack]
            [org.clojuriststogether.app.slack-test :as fixtures]
            [org.clojuriststogether.app.stripe :as stripe]
            [org.clojuriststogether.app.webhooks-test :as signatures])
  (:import (java.util UUID)))

(defn port [server] (.getLocalPort (first (.getConnectors server))))

(deftest signed-http-membership-and-durable-slack-delivery
  (if-let [url (System/getenv "SLACK_TEST_DATABASE_URL")]
    (jdbc/with-db-connection [db {:connection-uri url}]
      (let [schema (str "slack_test_" (str/replace (str (UUID/randomUUID)) "-" ""))
            delivered (atom [])
            fail? (atom true)
            receiver (jetty/run-jetty (fn [req]
                                        (if @fail?
                                          {:status 500 :body "temporary failure"}
                                          (do (swap! delivered conj (json/parse-string (slurp (:body req)) true))
                                              {:status 200 :body "ok"}))) {:port 0 :join? false})]
        (jdbc/db-do-commands db (str "CREATE SCHEMA " schema))
        (try
          (jdbc/db-do-commands db (str "SET search_path TO " schema))
          (jdbc/db-do-commands db "CREATE TABLE members (id INTEGER PRIMARY KEY,person_name TEXT,organization_name TEXT,email TEXT,stripe_customer_id TEXT,subscription_plan TEXT)")
          (jdbc/execute! db ["INSERT INTO members VALUES (1,'Member',NULL,'member@example.org','cus_example',NULL)"])
          (doseq [sql (str/split (slurp (io/resource "migrations/006-slack-notifications.up.sql")) #";") :when (not (str/blank? sql))]
            (jdbc/db-do-commands db sql))
          (let [app (jetty/run-jetty (ig/init-key :app/handler {:profile :test :db db :stripe-webhook-secret signatures/secret}) {:port 0 :join? false})
                post (fn [event]
                       (let [body (json/generate-string (assoc event :api_version "2026-11-18.endive" :object "event"))]
                         (:status (http/post (str "http://127.0.0.1:" (port app) "/webhook/stripe")
                                             {:body body :headers {"Stripe-Signature" (signatures/signature-header body signatures/secret)}
                                              :throw-exceptions false}))))
                count-rows #(-> (jdbc/query db ["SELECT count(*) AS n FROM stripe_slack_notifications"]) first :n)
                plan #(-> (jdbc/query db ["SELECT subscription_plan FROM members WHERE id=1"]) first :subscription_plan)
                created (fixtures/event "created" fixtures/subscription {})
                scheduled (-> (fixtures/event "updated" (assoc fixtures/subscription :cancel_at_period_end true) {:cancel_at_period_end false}) (assoc :id "evt_scheduled"))
                hook (str "http://127.0.0.1:" (port receiver))]
            (try
              (is (= 200 (post created)))
              (is (= "price_annual" (plan)))
              (is (= 200 (post created)))
              (is (= 1 (count-rows)))
              (is (= 200 (post scheduled)))
              (is (= "price_annual" (plan)) "Scheduling cancellation retains membership")
              (is (= 2 (count-rows)))
              (is (= 200 (post (assoc-in (assoc scheduled :id "evt_metadata") [:data :previous_attributes] {:metadata {}}))))
              (is (= 2 (count-rows)))
              (with-redefs [stripe/notification-object (fn [kind _] (case kind :customer {:name "Corporate Customer" :email "billing@example.org"} :product {:name "Corporate membership"}))]
                (slack/deliver-once! db hook)
                (is (empty? @delivered))
                (is (= 2 (:n (first (jdbc/query db ["SELECT count(*) AS n FROM stripe_slack_notifications WHERE delivered_at IS NULL AND attempts=1 AND message IS NOT NULL AND last_error='ExceptionInfo HTTP 500'"])))))
                (reset! fail? false)
                (jdbc/execute! db ["UPDATE stripe_slack_notifications SET retry_at=now()"])
                (slack/deliver-once! db hook)
                (slack/deliver-once! db hook)
                (is (= 2 (count @delivered)))
                (is (some #(.contains (:text %) "Subscription cancellation scheduled") @delivered))
                (is (every? #(.contains (:text %) "Corporate membership — Annual — USD 1200 per year") @delivered))
                (is (= 2 (:n (first (jdbc/query db ["SELECT count(*) AS n FROM stripe_slack_notifications WHERE delivered_at IS NOT NULL AND attempts=2"])))))
                (is (= 200 (post (assoc (fixtures/event "deleted" fixtures/subscription {}) :id "evt_deleted"))))
                (is (nil? (plan)))
                (slack/deliver-once! db hook)
                (is (= 3 (count @delivered))))
              (with-redefs [slack/enqueue! (fn [& _] (throw (ex-info "Simulated queue failure" {})))]
                (is (= 500 (post (assoc created :id "evt_rollback")))))
              (is (nil? (plan)) "Membership write rolls back when queue write fails")
              (is (= 3 (count-rows)))
              (finally (.stop app))))
          (finally
            (.stop receiver)
            (jdbc/db-do-commands db "SET search_path TO public")
            (jdbc/db-do-commands db (str "DROP SCHEMA " schema " CASCADE"))))))
    (println "Skipping Slack HTTP/PostgreSQL test; set SLACK_TEST_DATABASE_URL to a disposable database.")))
