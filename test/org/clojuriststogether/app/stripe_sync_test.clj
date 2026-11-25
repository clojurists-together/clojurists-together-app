(ns org.clojuriststogether.app.stripe-sync-test
  (:require [clojure.test :refer [deftest is testing]]
            [org.clojuriststogether.app.stripe-sync :as stripe-sync]))

(def member
  {:id 1
   :email "ada@example.com"
   :invoicing_email nil
   :person_name "Ada Lovelace"
   :stripe_customer_id "cus_ada"
   :subscription_plan "plan_a"})

(def customer {:id "cus_ada" :email "ada@example.com"})

(deftest matching-email-needs-nothing
  (is (empty? (stripe-sync/plan [member] [customer])))
  (testing "case doesn't count as a difference"
    (is (empty? (stripe-sync/plan [member] [(assoc customer :email "ADA@example.com")])))))

(deftest app-billing-email-goes-to-stripe
  (testing "the billing email wins over the login email"
    (let [[op :as ops] (stripe-sync/plan [(assoc member :invoicing_email "accounts@example.com")] [customer])]
      (is (= 1 (count ops)))
      (is (= [:set-stripe-email "cus_ada" "ada@example.com" "accounts@example.com"]
             ((juxt :op :contact :from :to) op)))))
  (testing "the login email is used when no billing email is set"
    (let [[op] (stripe-sync/plan [member] [(assoc customer :email "old@example.com")])]
      (is (= ["old@example.com" "ada@example.com"] ((juxt :from :to) op))))))

(deftest every-stripe-change-waits-for-review
  (doseq [customers [[(assoc customer :email "old@example.com")] []]]
    (is (= [:review] (map :disposition (stripe-sync/plan [member] customers))))))

(deftest members-without-a-plan-are-skipped
  (is (empty? (stripe-sync/plan [(assoc member :subscription_plan nil)] [(assoc customer :email "old@example.com")]))))

(deftest missing-customer-is-flagged
  (let [[op] (stripe-sync/plan [member] [])]
    (is (= [:flag :no-stripe-customer] ((juxt :op :reason) op)))))
