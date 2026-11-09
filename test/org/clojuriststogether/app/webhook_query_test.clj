(ns org.clojuriststogether.app.webhook-query-test
  (:require [clojure.java.jdbc :as jdbc]
            [clojure.test :refer [are deftest is]]
            [org.clojuriststogether.app.webhooks :as webhooks]))

(defn event [type customer plan-id]
  {:type type
   :data {:object {:customer customer
                   :plan {:id plan-id}}}})

(defn executed-sql [payload]
  (let [executed (atom nil)]
    (with-redefs [jdbc/execute! (fn [_ sql-params] (reset! executed sql-params))]
      (webhooks/handle-subscription-event nil payload))
    @executed))

(deftest subscription-events-set-the-members-plan
  (is (= ["UPDATE members SET subscription_plan = ? WHERE stripe_customer_id = ?" "plan_1" "cus_1"]
         (executed-sql (event "customer.subscription.updated" "cus_1" "plan_1")))))

(deftest deleted-subscriptions-clear-the-members-plan
  (is (= ["UPDATE members SET subscription_plan = NULL WHERE stripe_customer_id = ?" "cus_1"]
         (executed-sql (event "customer.subscription.deleted" "cus_1" "plan_1")))))

(deftest collections-in-a-payload-never-reach-the-query
  (are [customer plan-id]
       (let [executed (atom false)]
         (with-redefs [jdbc/execute! (fn [_ _] (reset! executed true))]
           (is (thrown-with-msg? clojure.lang.ExceptionInfo #"must be scalars"
                                 (webhooks/handle-subscription-event
                                  nil (event "customer.subscription.updated" customer plan-id)))))
         (not @executed))
    {:raw "'cus_1'"} "plan_1"
    "cus_1" {:raw "'plan_1'"}
    ["cus_1" "cus_2"] "plan_1"
    "cus_1" ["plan_1" "plan_2"]))
