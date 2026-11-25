(ns org.clojuriststogether.app.cashflow
  (:require [clojure.string :as str])
  (:import (java.time Instant ZoneOffset YearMonth ZonedDateTime)))

(def assumptions
  "Actuals are successful Stripe subscription payments less recorded Stripe fees, by payment month in UTC. Forecast renewals deduct estimated fees and assume successful payment with unchanged plans and quantities. Annual payments appear in their payment or renewal month. Manual-collection renewals are expected on the renewal date plus the invoice payment terms. Refunds, disputes, payments outside Stripe, separately billed fees and expenses are excluded; payout timing is ignored.")

(def timing "Completed months show actuals. The current month is a full-month forecast: receipts collected so far plus upcoming renewals. Later months show forecast renewals. Cancellation scenarios only affect future receipts.")

(def fee-method "Future fees use each member's three most recent payments in the same currency from the last 12 months. Matching payment amounts use the average recorded fee; changed amounts use the weighted effective rate. Members without usable history use the fallback percentage and fixed fee. Actuals always use recorded fees.")

(defn utc [seconds]
  (when seconds (.atZone (Instant/ofEpochSecond seconds) ZoneOffset/UTC)))

(defn continuing-schedule? [s]
  (let [schedule (:schedule s)
        final-end (:end_date (last (:phases schedule)))
        current-end (get-in schedule [:current_phase :end_date])]
    (and (map? schedule) (= "active" (:status schedule))
         (= "release" (:end_behavior schedule))
         (number? final-end) (= final-end current-end)
         (number? (:current_period_end s)) (<= final-end (:current_period_end s)))))

(defn unsupported [s]
  (cond
    (not (#{"active" "trialing"} (:status s))) (str "status " (:status s))
    (:pause_collection s) "paused collection"
    (and (:schedule s) (not (continuing-schedule? s))) "subscription schedule with future or unknown changes"
    (:pending_update s) "pending plan change"
    (seq (get-in s [:discount :coupon :applies_to :products])) "product-specific coupon"
    (= "once" (get-in s [:discount :coupon :duration])) "one-time coupon requires invoice review"
    (:billing_thresholds s) "billing thresholds"
    (nil? (:current_period_end s)) "missing next renewal"
    (empty? (get-in s [:items :data])) "missing subscription items"
    (some (fn [item]
            (let [p (:plan item)]
              (or (nil? (:amount p))
                  (not (#{"day" "week" "month" "year"} (:interval p)))
                  (not (pos? (or (:interval_count p) 0)))
                  (not= "licensed" (:usage_type p))
                  (not= "per_unit" (:billing_scheme p))
                  (:transform_usage p) (:billing_thresholds item))))
          (get-in s [:items :data])) "unsupported usage or pricing"
    (> (count (set (map #(select-keys (:plan %) [:interval :interval_count :currency])
                        (get-in s [:items :data])))) 1) "mixed billing intervals or currencies"
    (and (:discount s) (nil? (get-in s [:discount :coupon]))) "missing coupon details"))

(defn renewal-date [s n]
  (let [start (utc (if (= "trialing" (:status s))
                     (or (:trial_end s) (:current_period_end s))
                     (:current_period_end s)))
        p (get-in s [:items :data 0 :plan])
        step (* n (:interval_count p))
        anchor (or (utc (:billing_cycle_anchor s)) start)
        date (case (:interval p)
               "day" (.plusDays start step)
               "week" (.plusWeeks start step)
               "month" (.plusMonths start step)
               "year" (.plusYears start step))]
    (if (or (zero? n) (#{"day" "week"} (:interval p)))
      date
      (.withDayOfMonth date (min (.getDayOfMonth anchor)
                                (.lengthOfMonth (YearMonth/from date)))))))

(defn charge [s date]
  (let [gross (reduce + (map #(* (get-in % [:plan :amount]) (or (:quantity %) 1))
                             (get-in s [:items :data])))
        {:keys [coupon start end]} (:discount s)
        seconds (.toEpochSecond ^ZonedDateTime date)
        applies? (and coupon (or (nil? start) (<= start seconds))
                      (or (nil? end) (< seconds end))
                      (not= "once" (:duration coupon)))
        currency (get-in s [:items :data 0 :plan :currency])]
    (if applies?
      (max 0 (if-let [percent (:percent_off coupon)]
               (long (Math/round (double (* gross (- 1M (/ (bigdec percent) 100M))))))
               (- gross (if (= currency (:currency coupon)) (or (:amount_off coupon) 0) 0))))
      gross)))

(defn monthly-amount [s now]
  (let [plans (map :plan (get-in s [:items :data]))
        p (first plans)
        intervals (set (map #(select-keys % [:interval :interval_count :currency]) plans))
        divisor (when (pos? (or (:interval_count p) 0))
                  (some-> (case (:interval p) "month" 1M "year" 12M
                            "week" (/ 84.0 365.2425) "day" (/ 12.0 365.2425) nil)
                          (* (:interval_count p))))]
    (when (and (= 1 (count intervals)) divisor
               (every? #(and (number? (:amount %)) (not= "metered" (:usage_type %))) plans)
               (not (seq (get-in s [:discount :coupon :applies_to :products]))))
      {:currency (:currency p)
       :amount (.divide (bigdec (charge s (.atZone ^Instant now ZoneOffset/UTC)))
                        (bigdec divisor) 8 java.math.RoundingMode/HALF_UP)})))

(defn billing-details [s now]
  (let [invoice (:latest_invoice s)
        outstanding? (and (#{"open" "uncollectible"} (:status invoice)) (pos? (or (:amount_remaining invoice) 0)))
        renewal (or (when (= "trialing" (:status s)) (:trial_end s)) (:current_period_end s))
        cancelled? (or (:cancel_at_period_end s) (and (:cancel_at s) renewal (<= (:cancel_at s) renewal)))
        due (when (and renewal (not cancelled?) (#{"active" "trialing"} (:status s))
                       (not (:pause_collection s)) (or (nil? (:schedule s)) (continuing-schedule? s)))
              (+ renewal (* 86400 (if (= "send_invoice" (:collection_method s)) (or (:days_until_due s) 0) 0))))]
    (if outstanding?
      {:amount (:amount_remaining invoice) :currency (:currency invoice) :amount-label "Latest unpaid invoice"
       :due (some-> (:due_date invoice) utc .toLocalDate str)
       :due-label "Due" :next-attempt (some-> (:next_payment_attempt invoice) utc .toLocalDate str)
       :invoice-date (some-> (:created invoice) utc .toLocalDate str)}
      {:amount (when (monthly-amount s now) (charge s (or (utc renewal) (.atZone ^Instant now ZoneOffset/UTC))))
       :currency (get-in s [:items :data 0 :plan :currency]) :amount-label "Billing amount"
       :due (some-> due utc .toLocalDate str) :due-label "Expected due"
       :due-note (when cancelled? "No further renewal scheduled")})))

(defn warning [s member reason now]
  {:name (or (not-empty (:customer-name s)) (not-empty (:organization_name member))
             (not-empty (:person_name member)) "Unnamed Stripe customer")
   :url (str "https://dashboard.stripe.com/" (when (false? (:livemode s)) "test/") "subscriptions/" (:id s))
   :monthly (monthly-amount s now)
   :billing (billing-details s now)
   :reason reason})

(declare major)

(defn fee-models [history]
  (into {} (for [[key payments] (group-by (juxt :customer :currency)
                                         (filter #(and (pos? (or (:gross %) 0))
                                                       (number? (:fee %)) (not (neg? (:fee %)))
                                                       (= (:currency %) (:charge-currency %))) history))]
             [key (vec (take 3 (sort-by :created > payments)))])))

(defn estimated-fee [s currency gross opts]
  (let [samples (get (:fee-models opts) [(:customer s) currency])
        matching (filter #(= gross (:gross %)) samples)
        rounded-divide (fn [numerator denominator]
                         (.longValueExact (.divide (bigdec numerator) (bigdec denominator) 0 java.math.RoundingMode/HALF_UP)))]
    (cond
      (zero? gross) 0
      (seq matching) (rounded-divide (reduce + (map :fee matching)) (count matching))
      (seq samples) (rounded-divide (* gross (reduce + (map :fee samples))) (reduce + (map :gross samples)))
      :else (long (Math/round (double (+ (* gross (/ (or (:fee-percent opts) 0M) 100M))
                                        (/ (or (:fee-fixed opts) 0M) (major currency 1)))))))))

(defn receipts [s months cancel-month opts]
  (let [end (.atStartOfDay (.atDay (.plusMonths ^YearMonth (last months) 1) 1) ZoneOffset/UTC)
        cutoffs (keep identity [(:cancel_at s) (when (:cancel_at_period_end s) (:current_period_end s))])
        cutoff (when (seq cutoffs) (utc (apply min cutoffs)))
        dates (take-while #(.isBefore ^ZonedDateTime % end)
                          (map #(renewal-date s %) (range)))
        amounts (reduce (fn [acc date]
                          (let [receipt-date (if (= "send_invoice" (:collection_method s))
                                               (.plusDays ^ZonedDateTime date (or (:days_until_due s) 0))
                                               date)
                                month (YearMonth/from receipt-date)]
                            (if (and (some #{month} months)
                                     (or (nil? (:as-of opts)) (not (.isBefore (.toInstant ^ZonedDateTime date) (:as-of opts))))
                                     (or (nil? cutoff) (.isBefore ^ZonedDateTime date cutoff))
                                     (or (nil? cancel-month) (.isBefore month cancel-month)))
                              (let [gross (charge s date)
                                    currency (get-in s [:items :data 0 :plan :currency])
                                    fee (estimated-fee s currency gross opts)]
                                (update acc month (fnil + 0) (- gross fee))) acc))) {} dates)]
    (mapv #(get amounts % 0) months)))

(defn forecast
  ([members subscriptions start horizon cancellations]
   (forecast members subscriptions start horizon cancellations {}))
  ([members subscriptions start horizon cancellations opts]
  (let [months (mapv #(.plusMonths ^YearMonth start %) (range horizon))
        by-customer (group-by :stripe_customer_id members)
        classified (mapv (fn [s]
                           (let [matches (get by-customer (:customer s))
                                 member (first matches)]
                             {:subscription s :member member
                              :reason (or (when (not= 1 (count matches)) "customer has no unique member match")
                                          (when-not (#{"developer" "company"} (:member_type member)) "unknown member type")
                                          (unsupported s))})) subscriptions)
        mrr (into {} (for [[key items] (group-by (fn [{:keys [subscription member]}]
                                                  [(:id member) (get-in subscription [:items :data 0 :plan :currency])])
                                                (filter :member classified))
                           :let [amounts (map #(some-> (monthly-amount (:subscription %) (or (:as-of opts) (Instant/now))) :amount) items)]]
                       [key (when (every? some? amounts) (reduce + amounts))]))
        usable (remove :reason classified)
        rows (for [[[member currency] items]
                   (sort-by (fn [[[m c] _]] [c (or (:organization_name m) (:person_name m)) (:id m)])
                            (group-by (fn [{:keys [subscription member]}]
                                        [member (get-in subscription [:items :data 0 :plan :currency])]) usable))]
               {:id (:id member) :type (:member_type member) :currency currency :customer (:stripe_customer_id member)
                :name (if (= "company" (:member_type member))
                        (or (not-empty (:organization_name member)) (:person_name member)) (:person_name member))
                :mrr (get mrr [(:id member) currency] 0)
                :cancel-month (get cancellations (:id member))
                :baseline (apply mapv + (map #(receipts (:subscription %) months nil opts) items))
                :values (apply mapv + (map #(receipts (:subscription %) months (get cancellations (:id member)) opts) items))})
        mask-past (fn [values] (mapv (fn [month value]
                                      (when-not (and (:as-of opts) (.isBefore ^YearMonth month (YearMonth/from (.atZone ^Instant (:as-of opts) ZoneOffset/UTC)))) value)) months values))]
    {:months months :options opts :mrr mrr
     :rows (mapv #(-> % (update :baseline mask-past) (update :values mask-past)) rows)
     :warnings (mapv (fn [{:keys [subscription member reason]}]
                       (warning subscription member reason (or (:as-of opts) (Instant/now))))
                     (filter :reason classified))})))

(defn month-kind [forecast month]
  (if-let [now (get-in forecast [:options :as-of])]
    (if (.isBefore ^YearMonth month (YearMonth/from (.atZone ^Instant now ZoneOffset/UTC))) "Actual" "Forecast")
    "Forecast"))

(defn with-actuals [forecast members receipts]
  (let [months (:months forecast)
        by-customer (group-by :stripe_customer_id members)
        historical (for [[[customer currency] payments] (group-by (juxt :customer :currency) receipts)]
                     (let [matches (get by-customer customer)
                           member (when (= 1 (count matches)) (first matches))
                           amounts (reduce (fn [m p] (update m (YearMonth/from (utc (:created p))) (fnil + 0) (:net p))) {} payments)]
                       {:id (or (:id member) customer) :currency currency
                        :type (if (#{"developer" "company"} (:member_type member)) (:member_type member) "other")
                        :name (or (not-empty (:organization_name member)) (:person_name member)
                                  (:customer-name (first payments)) "Unmatched Stripe customer")
                        :actuals (mapv #(get amounts % 0) months)}))
        existing (into {} (map (juxt (juxt :id :currency) identity) (:rows forecast)))
        combined (reduce (fn [rows row]
                           (update rows [(:id row) (:currency row)]
                                   #(if % (assoc % :actuals (:actuals row)) row))) existing historical)
        zeros (vec (repeat (count months) 0))]
    (assoc forecast :rows
           (->> (vals combined)
                (map (fn [row]
                       (let [actuals (or (:actuals row) zeros)
                             merge-values (fn [values]
                                            (mapv (fn [month actual projected]
                                                    (if (= "Actual" (month-kind forecast month)) actual
                                                      (+ actual (or projected 0)))) months actuals (or values zeros)))]
                         (-> row (assoc :actuals actuals :mrr (get (:mrr forecast) [(:id row) (:currency row)] 0))
                             (update :baseline merge-values) (update :values merge-values)))))
                (sort-by (juxt :currency :name)) vec))))

(defn total [rows field n]
  (reduce #(mapv (fn [a b] (when (some? b) (+ (or a 0) b))) %1 (field %2)) (vec (repeat n 0)) rows))

(def zero-decimal-currencies #{"bif" "clp" "djf" "gnf" "jpy" "kmf" "krw" "mga" "pyg" "rwf" "ugx" "vnd" "vuv" "xaf" "xof" "xpf"})
(defn major [currency amount]
  (when (some? amount)
    (/ (bigdec amount) (if (zero-decimal-currencies currency) 1M 100M))))
