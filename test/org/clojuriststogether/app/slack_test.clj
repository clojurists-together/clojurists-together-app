(ns org.clojuriststogether.app.slack-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.java.jdbc :as jdbc]
            [org.clojuriststogether.app.slack :as slack]
            [org.clojuriststogether.app.stripe :as stripe]))

(def subscription
  {:id "sub_example" :customer "cus_example" :status "active" :cancel_at_period_end false :cancel_at nil
   :items {:data [{:quantity 1 :current_period_end 1798761600
                   :price {:id "price_annual" :nickname "Annual" :product "prod_company"
                           :currency "usd" :unit_amount 120000 :recurring {:interval "year" :interval_count 1}}}]}})

(defn event [type object previous]
  {:id "evt_example" :type (str "customer.subscription." type) :livemode true
   :data {:object object :previous_attributes previous}})

(deftest cancellation-notifies-when-requested-and-when-final
  (is (= :cancellation-scheduled (slack/notification-kind (event "updated" (assoc subscription :cancel_at_period_end true) {:cancel_at_period_end false}))))
  (is (= :cancellation-scheduled (slack/notification-kind (event "updated" (assoc subscription :cancel_at 1798761600) {:cancel_at nil}))))
  (is (= :cancellation-scheduled (slack/notification-kind (event "updated" (assoc subscription :cancel_at 1798761600) {:cancel_at 1798761500}))))
  (is (= :cancellation-reversed (slack/notification-kind (event "updated" subscription {:cancel_at_period_end true :cancel_at 1798761600}))))
  (is (= :cancelled (slack/notification-kind (event "deleted" (assoc subscription :status "canceled") {}))))
  (is (= :created (slack/notification-kind (event "created" subscription {}))))
  (is (= :updated (slack/notification-kind (event "updated" subscription {:items {:data []}}))))
  (is (nil? (slack/notification-kind (event "updated" subscription {:metadata {}}))))
  (is (nil? (slack/notification-kind (event "updated" (assoc subscription :cancel_at_period_end true) {:status "active"})))))

(defn rendered [e]
  (with-redefs [jdbc/query (fn [& _] [{:person_name "Local Person" :organization_name "Local Company" :email "local@example.org"}])
                stripe/notification-object (fn [kind _] (case kind :customer {:name "Acme Corporation" :email "billing@acme.example"}
                                                              :product {:name "Corporate membership"}))]
    (slack/message {} e)))

(deftest corporate-message-separates-customer-product-and-plan
  (let [m (rendered (event "created" subscription {}))]
    (doseq [text ["New subscription" "Acme Corporation" "billing@acme.example" "Corporate membership" "Annual" "USD 1200 per year"]]
      (is (.contains (:text m) text)))
    (is (= "plain_text" (get-in m [:blocks 1 :text :type])))
    (is (.contains (get-in m [:blocks 3 :elements 0 :text]) "https://dashboard.stripe.com/subscriptions/sub_example"))))

(deftest scheduled-cancellation-includes-the-effective-date
  (let [m (rendered (event "updated" (assoc subscription :cancel_at_period_end true) {:cancel_at_period_end false}))]
    (is (.contains (:text m) "Subscription cancellation scheduled"))
    (is (.contains (:text m) "remains active until 2027-01-01 00:00 UTC"))))

(deftest quantities-currencies-intervals-and-legacy-plans
  (doseq [[currency amount qty recurring expected]
          [["usd" 500 2 {:interval "month" :interval_count 3} "USD 10 every 3 months (quantity 2)"]
           ["jpy" 1200 1 {:interval "month"} "JPY 1200 per month"]
           ["kwd" 1234 1 {:interval "year"} "KWD 1.234 per year"]]]
    (let [s (assoc-in subscription [:items :data 0] {:quantity qty :price {:id "price_x" :product "prod_x" :currency currency
                                                                           :unit_amount amount :recurring recurring}})]
      (is (.contains (:text (rendered (event "created" s {}))) expected))))
  (let [s (-> subscription (dissoc :items) (assoc :plan {:id "legacy" :nickname "Monthly" :product "prod_x" :currency "usd" :unit_amount 500 :interval "month"}))]
    (is (.contains (:text (rendered (event "deleted" s {}))) "Monthly — USD 5 per month"))))

(deftest display-names-cannot-inject-slack-mentions
  (with-redefs [jdbc/query (fn [& _] [])
                stripe/notification-object (fn [kind _] (case kind :customer {:name "<!channel>"} :product {:name "<evil>"}))]
    (let [m (slack/message {} (event "created" subscription {}))]
      (is (= "plain_text" (get-in m [:blocks 1 :text :type])))
      (is (= "<!channel>" (get-in m [:blocks 1 :text :text]))))))

(deftest existing-new-customer-notification-is-preserved
  (let [e {:id "evt_customer" :type "customer.created" :livemode true
           :data {:object {:id "cus_example" :name "Named Customer" :email "customer@example.org"}}}
        m (rendered e)]
    (is (= :customer-created (slack/notification-kind e)))
    (is (.contains (:text m) "New customer\nNamed Customer (customer@example.org)"))
    (is (.contains (get-in m [:blocks 2 :elements 0 :text]) "customers/cus_example"))))
