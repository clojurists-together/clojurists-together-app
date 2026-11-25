(ns org.clojuriststogether.app.cashflow-test
  (:require [clojure.test :refer :all]
            [org.clojuriststogether.app.cashflow :as cf]
            [org.clojuriststogether.app.cashflow-xlsx :as xlsx]
            [org.clojuriststogether.app.pages.cashflow :as page]
            [org.clojuriststogether.app.pages.auth :as auth]
            [org.clojuriststogether.app.stripe :as stripe]
            [org.clojuriststogether.app.routes]
            [integrant.core :as ig]
            [ring.middleware.session.memory :as memory]
            [clojure.java.jdbc :as jdbc])
  (:import (java.time Instant YearMonth)
           (java.io ByteArrayInputStream)
           (org.apache.poi.xssf.usermodel XSSFWorkbook)))

(defn epoch [s] (.getEpochSecond (Instant/parse s)))
(def member {:id 1 :person_name "A Developer" :member_type "developer" :stripe_customer_id "cus_1"})
(def subscription
  {:id "sub_1" :customer "cus_1" :status "active"
   :current_period_end (epoch "2026-01-31T12:00:00Z")
   :billing_cycle_anchor (epoch "2025-12-31T12:00:00Z")
   :items {:data [{:quantity 2 :plan {:amount 1000 :currency "usd" :interval "month"
                                      :interval_count 1 :usage_type "licensed" :billing_scheme "per_unit"}}]}})

(defn values-for [s & [cancellations opts]]
  (-> (cf/forecast [member] [s] (YearMonth/of 2026 1) 24 (or cancellations {}) (or opts {})) :rows first :values))

(deftest renewal-dates-and-amounts
  (is (= "2026-02-28T12:00Z" (str (cf/renewal-date subscription 1))))
  (is (= "2026-03-31T12:00Z" (str (cf/renewal-date subscription 2))))
  (is (= (repeat 24 2000) (values-for subscription)))
  (let [annual (assoc-in subscription [:items :data 0 :plan :interval] "year")]
    (is (= [2000 2000] (keep-indexed #(when (pos? %2) %2) (values-for annual))))
    (is (= [0 12] (keep-indexed #(when (pos? %2) %1) (values-for annual)))))
  (is (= [2000 0 0] (take 3 (values-for (assoc-in subscription [:items :data 0 :plan :interval_count] 3))))))

(deftest cancellations-trials-and-fees
  (is (every? zero? (values-for (assoc subscription :cancel_at_period_end true))))
  (is (= [2000 0 0] (take 3 (values-for (assoc subscription :cancel_at (epoch "2026-02-01T00:00:00Z"))))))
  (is (= [2000 2000 0] (take 3 (values-for subscription {1 (YearMonth/of 2026 3)}))))
  (is (= 1870 (first (values-for subscription {} {:fee-percent 5M :fee-fixed 0.30M}))))
  (is (= [nil nil 2000] (take 3 (values-for subscription {} {:as-of (Instant/parse "2026-03-01T00:00:00Z")}))))
  (is (= [0 0 2000] (take 3 (values-for (assoc subscription :status "trialing" :trial_end (epoch "2026-03-01T00:00:00Z")))))))

(deftest discounts-and-unsupported
  (is (= 1500 (first (values-for (assoc subscription :discount {:coupon {:duration "forever" :percent_off 25}})))))
  (is (= [1500 2000] (take 2 (values-for (assoc subscription :discount {:end (epoch "2026-02-01T00:00:00Z") :coupon {:duration "repeating" :amount_off 500 :currency "usd"}})))))
  (doseq [s [(assoc subscription :status "past_due")
             (assoc subscription :pause_collection {:behavior "void"})
             (assoc subscription :schedule "sched_1")
             (assoc-in subscription [:items :data 0 :plan :usage_type] "metered")]]
    (let [f (cf/forecast [member] [s] (YearMonth/of 2026 1) 12 {})]
      (is (empty? (:rows f))) (is (= 1 (count (:warnings f)))))))

(deftest forecast-input-validation
  (let [now (Instant/parse "2026-01-01T00:00:00Z")]
    (is (= 24 (:horizon (page/options {} now))))
    (doseq [params [{"years" "0"} {"year" "not-a-year"} {"fee" "101"} {"fixed" "-1"}
                    {"cancel-1" "2026-99"}]]
      (is (thrown? clojure.lang.ExceptionInfo (page/options params now))))))

(deftest routed-page-and-export
  (let [current (atom (assoc member :is_admin true))
        handler (ig/init-key :app/handler {:profile :test :db {} :stripe {}
                                           :store (memory/memory-store (atom {"session" {:member-id 1}}))})]
    (with-redefs [auth/get-member (fn [& _] @current)
                  jdbc/query (fn [& _] [member])
                  stripe/snapshot (fn [_] {:as-of (Instant/now) :subscriptions [subscription] :actuals []})]
      (doseq [path ["/admin/cashflow" "/admin/cashflow.xlsx"]]
        (let [req {:request-method :get :uri path :scheme :http :server-name "localhost"
                   :server-port 3000 :headers {"cookie" "ring-session=session"}}
              result (handler req)]
          (is (= 200 (:status result)))
          (is (= "no-store" (get-in result [:headers "Cache-Control"])))
          (when (= path "/admin/cashflow.xlsx")
            (with-open [workbook (XSSFWorkbook. (:body result))]
              (is (= "Cashflow" (.getSheetName workbook 0)))
              (is (= "Assumptions" (.getSheetName workbook 1)))))
          (reset! current member)
          (is (= 403 (:status (handler req))))
          (is (= 302 (:status (handler (assoc req :headers {})))))
          (reset! current (assoc member :is_admin true)))))))

(deftest actuals-and-current-month-projection
  (let [now (Instant/parse "2026-03-15T00:00:00Z")
        opts {:as-of now :fee-percent 5M}
        receipt (fn [date net customer]
                  {:created (epoch date) :net net :currency "usd" :customer customer :customer-name "Former company"})
        history [(receipt "2026-01-20T00:00:00Z" 1800 "cus_1")
                 (receipt "2026-02-01T00:00:00Z" 1810 "cus_1")
                 (receipt "2026-03-10T00:00:00Z" 1820 "cus_1")
                 (receipt "2026-01-10T00:00:00Z" 9000 "cus_former")]
        f (-> (cf/forecast [member] [subscription] (YearMonth/of 2026 1) 5 {1 (YearMonth/of 2026 3)} opts)
              (cf/with-actuals [member] history))
        developer (first (filter #(= 1 (:id %)) (:rows f)))
        other (first (filter #(= "other" (:type %)) (:rows f)))]
    (is (= [1800 1810 3720 1900 1900] (:baseline developer)))
    (is (= [1800 1810 1820 0 0] (:values developer)))
    (is (= [9000 0 0 0 0] (:values other)))
    (is (= "Actual" (cf/month-kind f (YearMonth/of 2026 2))))
    (is (= "Forecast" (cf/month-kind f (YearMonth/of 2026 3))))
    (is (= [10800 1810 1820 0 0] (cf/total (:rows f) :values 5)))
    (with-open [workbook (XSSFWorkbook. (ByteArrayInputStream. (xlsx/workbook f now)))]
      (let [sheet (.getSheet workbook "Cashflow")
            cell (fn [row column] (.getCell (.getRow sheet row) column))
            total (cell 2 2)]
        (is (= "2026-01 Actual" (.getStringCellValue (cell 0 2))))
        (is (= "2026-03 Forecast" (.getStringCellValue (cell 0 4))))
        (is (= 108.0 (.getNumericCellValue total)))
        (is (= "SUM(C4:C5)" (.getCellFormula total)))
        (is (= 1 (.getOutlineLevel (.getRow sheet 3))))
        (is (= 2 (.getVerticalSplitPosition (.getPaneInformation sheet))))
        (.setCellValue (cell 3 2) 200.0)
        (.evaluateAll (.createFormulaEvaluator (.getCreationHelper workbook)))
        (is (= 290.0 (.getNumericCellValue total)))))))

(deftest stripe-actual-payment-normalization
  (let [charge {:paid true :status "succeeded" :created 100 :currency "usd"
                :customer {:id "cus_1" :name "Customer"}
                :invoice {:subscription "sub_old"}
                :balance_transaction {:currency "usd" :amount 1000 :net 970 :fee 30}}]
    (is (= {:customer "cus_1" :customer-name "Customer" :subscription "sub_old"
            :created 100 :currency "usd" :net 970 :gross 1000 :fee 30 :charge-currency "usd"} (stripe/charge-receipt charge)))
    (is (nil? (stripe/charge-receipt (assoc charge :paid false))))
    (is (nil? (stripe/charge-receipt (assoc charge :invoice nil))))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"no recorded Stripe fee"
                          (stripe/charge-receipt (assoc charge :balance_transaction nil))))))

(deftest named-linked-subscription-warnings
  (let [s (-> subscription
              (assoc :status "past_due" :customer-name "Company & Co" :livemode true)
              (assoc-in [:items :data 0 :plan :interval] "year")
              (assoc-in [:items :data 0 :plan :amount] 12000))
        now (Instant/parse "2026-01-01T00:00:00Z")
        f (cf/forecast [member] [s] (YearMonth/of 2026 1) 12 {} {:as-of now})
        warning (first (:warnings f))
        html (page/page f {:start (YearMonth/of 2026 1) :horizon 12} now)]
    (is (= "Company & Co" (:name warning)))
    (is (= 2000M (get-in warning [:monthly :amount])))
    (is (= "https://dashboard.stripe.com/subscriptions/sub_1" (:url warning)))
    (is (.contains html "Company &amp; Co"))
    (is (.contains html "USD 20.00/month"))
    (is (.contains html "href=\"https://dashboard.stripe.com/subscriptions/sub_1\""))
    (is (nil? (cf/monthly-amount (assoc-in s [:items :data 0 :plan :amount] nil) now)))))

(deftest member-fee-estimates
  (let [payment (fn [created gross fee] {:customer "cus_1" :created created :gross gross :fee fee :currency "usd" :charge-currency "usd"})
        models (cf/fee-models [(payment 1 2000 150) (payment 2 2000 60)
                               (payment 3 2000 70) (payment 4 4000 100)
                               (assoc (payment 5 2000 90) :charge-currency "eur")])
        opts {:fee-models models :fee-percent 5M :fee-fixed 0.30M}]
    (is (= 3 (count (get models ["cus_1" "usd"]))))
    (is (= 65 (cf/estimated-fee subscription "usd" 2000 opts)))
    (is (= 100 (cf/estimated-fee subscription "usd" 4000 opts)))
    (is (= 173 (cf/estimated-fee subscription "usd" 6000 opts)))
    (is (= 130 (cf/estimated-fee (assoc subscription :customer "new") "usd" 2000 opts)))
    (is (= 130 (cf/estimated-fee subscription "eur" 2000 opts)))
    (is (= 0 (cf/estimated-fee subscription "usd" 0 opts)))
    (is (= 1935 (first (values-for subscription {} opts))))))

(deftest excluded-billing-amounts-and-dates
  (let [now (Instant/parse "2026-01-01T00:00:00Z")
        manual (assoc subscription :collection_method "send_invoice" :days_until_due 30)
        billing (cf/billing-details manual now)
        unpaid (assoc subscription :status "past_due" :latest_invoice
                      {:status "open" :amount_due 2000 :amount_remaining 500 :currency "usd"
                       :created (epoch "2025-12-01T12:00:00Z")
                       :due_date (epoch "2025-12-31T12:00:00Z")
                       :next_payment_attempt (epoch "2026-01-05T12:00:00Z")})]
    (is (= 2000 (:amount billing)))
    (is (= "2026-03-02" (:due billing)))
    (is (= "Expected due" (:due-label billing)))
    (is (= {:amount 500 :currency "usd" :amount-label "Latest unpaid invoice"
            :due "2025-12-31" :due-label "Due" :next-attempt "2026-01-05" :invoice-date "2025-12-01"}
           (cf/billing-details unpaid now)))
    (is (nil? (:due (cf/billing-details (assoc-in unpaid [:latest_invoice :due_date] nil) now))))
    (is (= "No further renewal scheduled" (:due-note (cf/billing-details (assoc manual :cancel_at_period_end true) now))))
    (is (nil? (:due (cf/billing-details (assoc manual :schedule "sched_1") now))))))

(deftest manual-collection-renewals
  (let [s (-> subscription
              (assoc :collection_method "send_invoice" :days_until_due 30
                     :current_period_end (epoch "2027-06-20T00:00:00Z")
                     :billing_cycle_anchor (epoch "2026-06-20T00:00:00Z")
                     :latest_invoice {:status "void" :amount_remaining 1200000})
              (assoc-in [:items :data 0 :quantity] 1)
              (assoc-in [:items :data 0 :plan :amount] 1200000)
              (assoc-in [:items :data 0 :plan :interval] "year"))
        opts {:as-of (Instant/parse "2026-11-25T00:00:00Z") :fee-percent 5M}
        f (cf/forecast [member] [s] (YearMonth/of 2026 1) 24 {} opts)
        row (first (:rows f))]
    (is (empty? (:warnings f)))
    (is (= 0 (nth (:values row) 17)))
    (is (= 1140000 (nth (:values row) 18)))
    (is (= 1140000 (reduce + (keep identity (:values row)))))
    (is (= 0 (nth (values-for s {1 (YearMonth/of 2027 7)} opts) 18)))
    (is (= 0 (reduce + (keep identity (values-for (assoc s :cancel_at_period_end true) {} opts)))))
    (is (= 1140000 (nth (values-for (assoc s :days_until_due 0) {} opts) 17)))
    (let [december (assoc s :current_period_end (epoch "2026-12-20T00:00:00Z"))]
      (is (= 1140000 (nth (values-for december {} opts) 12)))
      (is (zero? (nth (values-for december {} opts) 11))))))

(deftest terminal-release-schedules-continue-renewing
  (let [end (:current_period_end subscription)
        schedule {:status "active" :end_behavior "release"
                  :current_phase {:start_date (- end 1000) :end_date end}
                  :phases [{:start_date (- end 1000) :end_date end}]}
        s (assoc subscription :schedule schedule)]
    (is (= (values-for subscription) (values-for s)))
    (is (= "2026-01-31" (:due (cf/billing-details s (Instant/parse "2026-01-01T00:00:00Z")))))
    (is (empty? (:rows (cf/forecast [member] [(assoc-in s [:schedule :end_behavior] "cancel")]
                                    (YearMonth/of 2026 1) 12 {}))))
    (is (empty? (:rows (cf/forecast [member] [(update-in s [:schedule :phases] conj {:start_date end :end_date (+ end 1000)})]
                                    (YearMonth/of 2026 1) 12 {}))))
    (is (= [2000 0 0] (take 3 (values-for s {1 (YearMonth/of 2026 2)}))))))

(deftest member-monthly-recurring-revenue
  (let [annual (-> subscription
                   (assoc-in [:items :data 0 :plan :interval] "year")
                   (assoc-in [:items :data 0 :plan :amount] 600000)
                   (assoc-in [:items :data 0 :quantity] 1))
        f (cf/forecast [member] [annual subscription] (YearMonth/of 2026 1) 24 {1 (YearMonth/of 2026 2)} {})]
    (is (= 52000M (:mrr (first (:rows f)))))
    (is (= "$520 MRR" (page/mrr-label "usd" (:mrr (first (:rows f))))))
    (is (= 39000M (:mrr (first (:rows (cf/forecast [member]
                                                   (map #(assoc % :discount {:coupon {:duration "forever" :percent_off 25}}) [annual subscription])
                                                   (YearMonth/of 2026 1) 24 {})))))))
  (is (= "$501 MRR" (page/mrr-label "usd" 50050M)))
  (is (= "$0 MRR" (page/mrr-label "usd" 0)))
  (is (= "MRR unavailable" (page/mrr-label "usd" nil)))
  (let [f (-> (cf/forecast [member] [] (YearMonth/of 2026 1) 12 {})
              (cf/with-actuals [member] [{:customer "cus_1" :created (epoch "2026-01-01T00:00:00Z") :currency "usd" :net 2000}]))]
    (is (= 0 (:mrr (first (:rows f)))))))
