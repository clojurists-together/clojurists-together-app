(ns org.clojuriststogether.app.subscription-events-test
  (:require [clojure.test :refer [deftest is]]
            [org.clojuriststogether.app.subscription-events :as events]))

(defn update-event [current previous]
  {:type "customer.subscription.updated" :data {:object current :previous_attributes previous}})

(deftest semantic-updates
  (doseq [[current previous title]
          [[{:status "past_due"} {:status "active"} "Payment failed — subscription past due"]
           [{:status "active"} {:status "past_due"} "Payment recovered — subscription active"]
           [{:status "active"} {:status "trialing"} "Trial ended — subscription active"]
           [{:status "paused"} {:status "active"} "Subscription paused"]
           [{:status "active"} {:status "paused"} "Subscription resumed"]
           [{:pause_collection {:behavior "void"}} {:pause_collection nil} "Payment collection paused"]
           [{:trial_end 200} {:trial_end 100} "Subscription trial changed"]
           [{:billing_mode {:type "flexible"}} {:billing_mode {:type "classic"}} "Subscription billing settings changed"]]]
    (is (= title (:title (events/update-details (update-event current previous)))))))

(deftest partial-maps-and-business-items
  (let [price {:id "price_a" :currency "usd" :unit_amount 100 :recurring {:interval "month"}}
        item {:price price :quantity 2 :current_period_end 300}
        current {:items {:data [item]} :pause_collection {:behavior "void" :resumes_at 200}}]
    (is (nil? (events/update-details (update-event current {:pause_collection {:resumes_at 200}})))))
  (let [item {:price {:id "price_a" :active false :nickname "New" :metadata {:secret "x"}} :quantity 1}]
    (is (nil? (events/update-details (update-event {:items {:data [item]}}
                                                   {:items {:data [(-> item (assoc :current_period_end 2)
                                                                       (assoc-in [:price :active] true)
                                                                       (assoc-in [:price :nickname] "Old"))]}})))))
  (is (= :items (-> (events/update-details (update-event {:items {:data [{:price "price_b" :quantity 1}]}}
                                                         {:items {:data []}})) :changes first :field))))

(deftest privacy-and-routine-suppression
  (is (nil? (events/update-details (update-event {:metadata {:x "secret"} :latest_invoice "in_new"}
                                                 {:metadata {} :latest_invoice "in_old"}))))
  (let [details (events/update-details (update-event {:future_setting {:secret "do-not-display"}}
                                                     {:future_setting {:secret "old-secret"}}))]
    (is (= "Subscription updated" (:title details)))
    (is (= :withheld (-> details :changes first :format)))
    (is (not (.contains (pr-str details) "secret")))))

(deftest every-status-and-mixed-priority
  (doseq [[status title] [["trialing" "Subscription trial started"] ["unpaid" "Subscription unpaid"]
                          ["canceled" "Subscription ended"] ["incomplete" "Subscription awaiting payment"]
                          ["incomplete_expired" "Subscription initial payment expired"]
                          ["future_status" "Subscription status changed"]]]
    (let [result (events/update-details (update-event {:status status} {:status "active"}))]
      (is (= title (:title result)))
      (is (= :status (-> result :changes first :field)))))
  (let [result (events/update-details
                (update-event {:cancel_at 200 :status "past_due" :metadata {:secret "private"}}
                              {:cancel_at nil :status "active" :metadata {}}))]
    (is (= :cancellation-scheduled (:kind result)))
    (is (= #{:cancel_at :status} (set (map :field (:changes result)))))))

(deftest reference-and-expanded-price-noise
  (let [item {:quantity 1 :discounts ["di_a" "di_b"] :tax_rates ["txr_a"]
              :price {:id "price_a" :product "prod_a" :recurring {:interval "month"}}}
        noisy (-> item (assoc :discounts [{:id "di_b" :metadata {:private "x"}} {:id "di_a"}])
                  (assoc :tax_rates [{:id "txr_a"}])
                  (assoc-in [:price :product] {:id "prod_a" :name "Renamed" :metadata {:private "x"}})
                  (assoc-in [:price :recurring :trial_period_days] 30))]
    (is (nil? (events/update-details (update-event {:items {:data [noisy]}} {:items {:data [item]}})))))
  (is (nil? (events/update-details (update-event {:discounts ["di_b" "di_a"]}
                                                 {:discounts [{:id "di_a"} {:id "di_b"}]})))))

(deftest legacy-and-multiple-item-changes
  (doseq [[current previous] [[{:plan {:id "plan_b"} :quantity 2} {:plan {:id "plan_a"} :quantity 1}]
                              [{:items {:data []}} {:items {:data [{:price "price_a"}]}}]
                              [{:items {:data [{:price "price_a"} {:price "price_b"}]}}
                               {:items {:data [{:price "price_a"}]}}]]]
    (is (= "Subscription membership changed" (:title (events/update-details (update-event current previous)))))))

(deftest financial-and-unknown-fields-default-to-safe-alerts
  (doseq [field [:application_fee_percent :transfer_data :on_behalf_of :future_setting]]
    (let [result (events/update-details (update-event {field {:secret "private"}} {field nil}))]
      (is (= :updated (:kind result)))
      (is (= :withheld (-> result :changes first :format)))
      (is (not (.contains (pr-str result) "private"))))))

(deftest remaining-known-categories
  (doseq [[current previous title]
          [[{:pause_collection nil} {:pause_collection {:behavior "void"}} "Payment collection resumed"]
           [{:discounts ["di_new"]} {:discounts []} "Subscription discount changed"]
           [{:automatic_tax {:enabled true}} {:automatic_tax {:enabled false}} "Subscription tax settings changed"]
           [{:default_tax_rates ["txr_new"]} {:default_tax_rates []} "Subscription tax settings changed"]
           [{:schedule "sub_sched_new"} {:schedule nil} "Subscription schedule changed"]
           [{:pending_update {:expires_at 200}} {:pending_update nil} "Subscription pending update changed"]
           [{:cancel_at 200} {:cancel_at 100} "Subscription cancellation date changed"]
           [{:cancel_at nil} {:cancel_at 100} "Subscription cancellation reversed"]]]
    (is (= title (:title (events/update-details (update-event current previous)))))))

(deftest reviewed-legacy-reference-and-pause-cases
  (let [result (events/update-details
                (update-event {:plan {:id "plan_new"}
                               :items {:data [{:plan {:id "plan_new"} :quantity 1}]}}
                              {:plan {:id "plan_old"}}))]
    (is (= "Subscription membership changed" (:title result)))
    (is (= "plan_old" (get-in result [:changes 0 :before 0 :price :id])))
    (is (= "plan_new" (get-in result [:changes 0 :after 0 :price :id]))))
  (is (= "Payment collection settings changed"
         (:title (events/update-details
                  (update-event {:pause_collection {:behavior "void" :resumes_at 200}}
                                {:pause_collection {:resumes_at 100}})))))
  (is (= "sub_sched_123abc"
         (get-in (events/update-details (update-event {:schedule "sub_sched_123abc"} {:schedule nil}))
                 [:changes 0 :after])))
  (is (nil? (events/update-details
             (update-event {:items {:data [{:price {:id "price_a" :currency "usd" :unit_amount 100}}]}}
                           {:items {:data [{:price "price_a"}]}})))))

(deftest modern-item-trials-are-meaningful
  (let [item {:price {:id "price_a"} :quantity 1 :current_trial nil}
        after (assoc item :current_trial {:start_date 100 :end_date 200 :trial_offer "trial_offer_a"})
        result (events/update-details
                (update-event {:items {:data [after]} :status "active"}
                              {:items {:data [item]}}))]
    (is (= "Subscription item trial changed" (:title result)))
    (is (= {:start_date 100 :end_date 200 :trial_offer "Details withheld"}
           (get-in result [:changes 0 :after 0 :current_trial])))))
