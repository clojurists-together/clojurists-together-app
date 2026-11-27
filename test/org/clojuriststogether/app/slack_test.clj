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

(deftest routine-renewals-do-not-notify
  (let [previous-item (assoc (get-in subscription [:items :data 0])
                             :id "si_example" :current_period_start 1796083200)
        renewed-item (assoc previous-item :current_period_start 1798761600 :current_period_end 1801440000)
        renewed (assoc-in subscription [:items :data] [renewed-item])
        previous {:items {:data [previous-item]} :latest_invoice "in_previous"}
        renewal (event "updated" renewed previous)]
    (is (nil? (slack/notification-kind renewal)))
    (testing "routine renewals never enter the Slack outbox"
      (let [writes (atom [])]
        (with-redefs [jdbc/execute! (fn [& args] (swap! writes conj args))]
          (slack/enqueue! {} renewal))
        (is (empty? @writes))))
    (testing "meaningful changes during a renewal still notify"
      (doseq [changed [(assoc-in renewed [:items :data 0 :quantity] 2)
                       (assoc-in renewed [:items :data 0 :price :id] "price_monthly")
                       (assoc-in renewed [:items :data 0 :discounts] ["di_example"])
                       (assoc-in renewed [:items :data] [])]]
        (is (= :updated (slack/notification-kind (event "updated" changed previous))))))
    (is (= :updated (slack/notification-kind (event "updated" (assoc renewed :status "past_due")
                                                    (assoc previous :status "active")))))
    (is (= :cancellation-scheduled
           (slack/notification-kind (event "updated" (assoc renewed :cancel_at_period_end true)
                                           (assoc previous :cancel_at_period_end false)))))
    (is (= :cancellation-reversed
           (slack/notification-kind (event "updated" renewed (assoc previous :cancel_at_period_end true)))))))

(defn rendered [e]
  (with-redefs [jdbc/query (fn [& _] [{:person_name "Local Person" :organization_name "Local Company" :email "local@example.org"}])
                stripe/notification-object (fn [kind _] (case kind :customer {:name "Acme Corporation" :email "billing@acme.example"}
                                                              :product {:name "Corporate membership"}))]
    (slack/message {} e)))

(deftest payment-status-transitions-have-specific-headings
  (let [m (rendered (event "updated" (assoc subscription :status "past_due") {:status "active"}))]
    (is (= "*Payment failed — subscription past due*" (get-in m [:attachments 0 :blocks 0 :text :text])))
    (is (.startsWith (get-in m [:attachments 0 :fallback]) "Payment failed — subscription past due\n")))
  (let [m (rendered (event "updated" subscription {:status "past_due"}))]
    (is (= "*Payment recovered — subscription active*" (get-in m [:attachments 0 :blocks 0 :text :text])))
    (is (.startsWith (get-in m [:attachments 0 :fallback]) "Payment recovered — subscription active\n")))
  (is (= "*Trial ended — subscription active*"
         (get-in (rendered (event "updated" subscription {:status "trialing"})) [:attachments 0 :blocks 0 :text :text])))
  (doseq [e [(event "updated" (assoc subscription :status "past_due") {:items {:data []}})]]
    (is (= "*Subscription membership changed*" (get-in (rendered e) [:attachments 0 :blocks 0 :text :text])))))

(deftest corporate-message-separates-customer-product-and-plan
  (let [m (rendered (event "created" subscription {}))]
    (doseq [text ["New subscription" "Acme Corporation" "billing@acme.example" "Corporate membership" "Annual" "USD 1200 per year"]]
      (is (.contains (get-in m [:attachments 0 :fallback]) text)))
    (is (= "rich_text" (get-in m [:attachments 0 :blocks 1 :type])))
    (is (= "*Amount*\nUSD 1,200.00" (get-in m [:attachments 0 :blocks 2 :fields 2 :text])))
    (is (.contains (get-in m [:attachments 0 :blocks 3 :elements 0 :text]) "https://dashboard.stripe.com/subscriptions/sub_example"))))

(deftest scheduled-cancellation-includes-the-effective-date
  (let [m (rendered (event "updated" (assoc subscription :cancel_at_period_end true) {:cancel_at_period_end false}))]
    (is (.contains (get-in m [:attachments 0 :fallback]) "Subscription cancellation scheduled"))
    (is (.contains (get-in m [:attachments 0 :fallback]) "remains active until 2027-01-01 00:00 UTC"))
    (is (= "#E59D23" (get-in m [:attachments 0 :color])))
    (is (.contains (get-in m [:attachments 0 :blocks 3 :text :text]) "<!date^1798761600^{date_long_pretty} at {time}|"))))

(deftest quantities-currencies-intervals-and-legacy-plans
  (doseq [[currency amount qty recurring expected]
          [["usd" 500 2 {:interval "month" :interval_count 3} "USD 10 every 3 months (quantity 2)"]
           ["jpy" 1200 1 {:interval "month"} "JPY 1200 per month"]
           ["kwd" 1234 1 {:interval "year"} "KWD 1.234 per year"]]]
    (let [s (assoc-in subscription [:items :data 0] {:quantity qty :price {:id "price_x" :product "prod_x" :currency currency
                                                                           :unit_amount amount :recurring recurring}})]
      (is (.contains (get-in (rendered (event "created" s {})) [:attachments 0 :fallback]) expected))))
  (let [s (-> subscription (dissoc :items) (assoc :plan {:id "legacy" :nickname "Monthly" :product "prod_x" :currency "usd" :unit_amount 500 :interval "month"}))]
    (is (.contains (get-in (rendered (event "deleted" s {})) [:attachments 0 :fallback]) "Monthly — USD 5 per month"))))

(deftest display-names-cannot-inject-slack-mentions
  (with-redefs [jdbc/query (fn [& _] [])
                stripe/notification-object (fn [kind _] (case kind :customer {:name "<!channel>"} :product {:name "<evil>"}))]
    (let [m (slack/message {} (event "created" subscription {}))]
      (is (= "rich_text" (get-in m [:attachments 0 :blocks 1 :type])))
      (is (= "<!channel>" (get-in m [:attachments 0 :blocks 1 :elements 0 :elements 0 :text]))))))

(deftest existing-new-customer-notification-is-preserved
  (let [e {:id "evt_customer" :type "customer.created" :livemode true
           :data {:object {:id "cus_example" :name "Named Customer" :email "customer@example.org"}}}
        m (rendered e)]
    (is (= :customer-created (slack/notification-kind e)))
    (is (.contains (get-in m [:attachments 0 :fallback]) "New customer\nNamed Customer (customer@example.org)"))
    (is (.contains (get-in m [:attachments 0 :blocks 2 :elements 0 :text]) "customers/cus_example"))))

(deftest readable-bounded-private-change-details
  (let [e (event "updated" (assoc subscription :status "past_due" :future_setting {:secret "private-new"}
                                  :cancellation_details {:comment "private-comment"})
                 {:status "active" :future_setting {:secret "private-old"}
                  :cancellation_details {:comment "old-comment"}})
        m (rendered e)
        text (pr-str m)]
    (doseq [expected ["What changed" "Status: Active → Past due" "future_setting: Changed (details withheld)" "View event in Stripe"]]
      (is (.contains text expected)))
    (doseq [private ["private-new" "private-old" "private-comment" "old-comment"]]
      (is (not (.contains text private)))))
  (let [long-name (apply str (repeat 5000 "<&>"))
        changed (-> subscription
                    (assoc-in [:items :data 0 :price :nickname] long-name)
                    (assoc-in [:items :data 0 :quantity] 2))]
    (with-redefs [jdbc/query (fn [& _] [])
                  stripe/notification-object (fn [kind _] (case kind :customer {:name long-name} :product {:name long-name}))]
      (let [m (slack/message {} (event "updated" changed {:items (:items subscription)}))
            blocks (get-in m [:attachments 0 :blocks])]
        (is (< (count blocks) 50))
        (is (<= (count (get-in m [:attachments 0 :fallback])) 8000))
        (doseq [block blocks]
          (when-let [text (get-in block [:text :text])]
            (is (<= (count text) 3000)))
          (doseq [field (:fields block)] (is (<= (count (:text field)) 2000))))
        (is (not (.contains (apply str (keep #(get-in % [:text :text]) blocks)) "<&>")))))))

(deftest item-settings-are-visible-and-private-data-are-omitted
  (let [changed (assoc-in subscription [:items :data 0 :discounts] [{:id "di_new" :metadata {:secret "private-discount"}}])
        m (rendered (event "updated" changed {:items (:items subscription)}))]
    (is (.contains (get-in m [:attachments 0 :fallback]) "di_new"))
    (is (not (.contains (pr-str m) "private-discount")))))

(deftest routine-private-updates-never-enter-outbox
  (doseq [[field value] [[:metadata {:private "x"}] [:default_payment_method "pm_new"]
                         [:cancellation_details {:comment "private"}] [:latest_invoice "in_new"]]]
    (let [writes (atom [])]
      (with-redefs [jdbc/execute! (fn [& args] (swap! writes conj args))]
        (slack/enqueue! {} (event "updated" (assoc subscription field value) {field nil})))
      (is (empty? @writes)))))

(deftest malformed-item-settings-never-dump-values
  (let [changed (-> subscription
                    (assoc-in [:items :data 0 :discounts] [{:id "private-token" :metadata {:secret "private-metadata"}}])
                    (assoc-in [:items :data 0 :billing_thresholds] {:usage_gte 10 :secret "private-threshold"}))
        m (rendered (event "updated" changed {:items (:items subscription)}))]
    (doseq [private ["private-token" "private-metadata" "private-threshold"]]
      (is (not (.contains (pr-str m) private))))
    (is (.contains (pr-str m) "usage_gte"))))

(deftest modern-item-trial-dates-are-readable
  (let [changed (assoc-in subscription [:items :data 0 :current_trial]
                          {:start_date 1798761600 :end_date 1801440000 :metadata {:secret "private-trial"}})
        m (rendered (event "updated" changed {:items (:items subscription)}))]
    (is (.contains (get-in m [:attachments 0 :fallback]) "Subscription item trial changed"))
    (is (.contains (get-in m [:attachments 0 :fallback]) "Trial: 2027-01-01 00:00 UTC to 2027-02-01 00:00 UTC"))
    (is (not (.contains (pr-str m) "private-trial")))))

(deftest item-trial-offer-changes-are-visible
  (let [before (assoc-in subscription [:items :data 0 :current_trial]
                         {:start_date 1798761600 :end_date 1801440000 :trial_offer "offer_a"})
        after (assoc-in before [:items :data 0 :current_trial :trial_offer] "offer_b")
        m (rendered (event "updated" after {:items (:items before)}))]
    (is (.contains (get-in m [:attachments 0 :fallback]) "offer_a"))
    (is (.contains (get-in m [:attachments 0 :fallback]) "offer_b"))))
