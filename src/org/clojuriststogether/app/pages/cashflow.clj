(ns org.clojuriststogether.app.pages.cashflow
  (:require [clojure.java.jdbc :as jdbc]
            [clojure.string :as str]
            [hiccup.core :as hiccup]
            [hiccup.util :as hu]
            [org.clojuriststogether.app.admin.auth :as admin]
            [org.clojuriststogether.app.cashflow :as cf]
            [org.clojuriststogether.app.cashflow-xlsx :as xlsx]
            [org.clojuriststogether.app.stripe :as stripe])
  (:import (java.time YearMonth Instant ZoneOffset)
           (java.io ByteArrayInputStream)
           (com.stripe.exception StripeException)))

(defn invalid! [] (throw (ex-info "Use a valid year, 1–5 years, fees from 0–100%, and cancellation months within the forecast." {:type ::invalid})))

(defn options [params now]
  (try
    (let [year (Integer/parseInt (get params "year" (str (.getYear (YearMonth/from (.atZone ^Instant now ZoneOffset/UTC))))))
          years (Integer/parseInt (get params "years" "2"))
          fee (bigdec (get params "fee" "5"))
          fixed (bigdec (get params "fixed" "0"))
          start (YearMonth/of year 1)
          end (.plusMonths start (* 12 years))
          cancellations (into {} (for [[k v] params :when (and (str/starts-with? k "cancel-") (not (str/blank? v)))]
                                   (let [month (YearMonth/parse v)]
                                     (when (or (.isBefore month start) (not (.isBefore month end))) (invalid!))
                                     [(Integer/parseInt (subs k 7)) month])))]
      (when-not (and (<= 2000 year 2100) (<= 1 years 5) (<= 0 fee 100) (<= 0 fixed 10000)) (invalid!))
      {:start start :horizon (* 12 years) :cancellations cancellations :fee-percent fee :fee-fixed fixed :as-of now
       :receipts-open (= "open" (get params "receipts"))})
    (catch Exception _ (invalid!))))

(defn month-class [forecast month]
  (let [current (some-> (get-in forecast [:options :as-of])
                        (.atZone ZoneOffset/UTC) YearMonth/from)]
    (str (cond (= month current) "month-current"
               (= "Actual" (cf/month-kind forecast month)) "month-past"
               :else "month-future")
         (when (= 1 (.getMonthValue ^YearMonth month)) " year-start"))))

(defn mrr-label [currency amount]
  (if (nil? amount) "MRR unavailable"
      (str (if (= "usd" currency) "$" (str (str/upper-case currency) " "))
           (format "%,d" (.longValueExact (.setScale (cf/major currency amount) 0 java.math.RoundingMode/HALF_UP)))
           " MRR")))

(defn receipt-row [forecast currency [label values member]]
  [:tr [:th.row-label {:scope "row"} (hu/escape-html label)
        (when member
          [:small.member-mrr.muted {:title "Current monthly recurring revenue, after recurring discounts and before Stripe fees. Cancellation scenarios do not change this reference amount."}
           (mrr-label currency (:mrr member))])
        (when (:cancel-month member)
          [:div [:small.muted (str "Simulated cancellation: " (:cancel-month member) " ")]
           [:button.receipt-action {:type "button" :data-input (str "cancel-" (:id member))
                                    :data-month ""
                                    :aria-label (str "Reset cancellation for " label)} "Reset"]])]
   (for [[month value] (map vector (:months forecast) values)]
     [:td {:class (month-class forecast month)}
      (let [amount (if (nil? value) "—" (format "%,.2f" (double (cf/major currency value))))]
        (if (and member (= "Forecast" (cf/month-kind forecast month)))
          [:button.receipt-action {:type "button" :data-input (str "cancel-" (:id member))
                                   :data-month (str month)
                                   :title (str "Simulate cancellation from " month)
                                   :aria-label (str "Simulate cancellation for " label " from " month "; net receipts " amount " " currency)}
           amount]
          amount))])])

(defn table [forecast rows currency membership-rows]
  [:div.forecast-scroll {:tabindex 0 :role "region" :aria-label (str (str/upper-case currency) " cashflow forecast")}
   [:table
    [:thead
     [:tr [:th.row-label {:rowspan 2 :scope "col"} "Member"]
      (for [year-months (partition-by #(.getYear ^YearMonth %) (:months forecast))]
        [:th.year-heading {:colspan (count year-months) :scope "colgroup"}
         (str (.getYear ^YearMonth (first year-months)))])]
     [:tr (for [month (:months forecast)]
            [:th {:class (month-class forecast month) :scope "col"}
             (.format ^YearMonth month (java.time.format.DateTimeFormatter/ofPattern "MMM" java.util.Locale/ENGLISH))
             [:br] [:small (if (str/includes? (month-class forecast month) "month-current")
                             "Current forecast" (cf/month-kind forecast month))]])]]
    [:tbody
     (map #(receipt-row forecast currency %) rows)
     [:tr
      [:th.row-label
       [:button.membership-toggle
        {:type "button" :aria-expanded (str (boolean (get-in forecast [:options :receipts-open]))) :aria-controls (str "membership-" currency)}
        "Membership receipts"]]
      [:td {:colspan (count (:months forecast))}]]]
    [:tbody.membership-details {:id (str "membership-" currency) :hidden (not (get-in forecast [:options :receipts-open]))}
     (map #(receipt-row forecast currency %) membership-rows)]]])

(defn page [forecast opts now]
  (let [{:keys [months rows warnings]} forecast]
    (hiccup/html
     [:html {:lang "en"}
      [:head [:meta {:charset "utf-8"}] [:meta {:name "viewport" :content "width=device-width, initial-scale=1"}]
       [:title "Cashflow forecast · Clojurists Together"]
       [:script {:src "/js/cashflow.js" :defer true}]
       [:link {:rel "stylesheet" :href "/css/cashflow.css"}]]
      [:body
       [:nav [:a {:href "/manage"} "← Manage account"]]
       [:h1 "Cashflow forecast"]
       [:p "Net subscription receipts for grant planning"]
       [:p.muted cf/assumptions]
       [:p.muted cf/timing]
       [:details [:summary "How fees are estimated"] [:p.muted cf/fee-method]]
       [:p.muted (str "Stripe snapshot: " now " (reused for up to five minutes)")]
       [:form {:method "get" :id "forecast-controls"}
        [:input {:type "hidden" :name "receipts" :id "receipts-state" :value (if (:receipts-open opts) "open" "")}]
        [:label "Starting year " [:input {:type "number" :name "year" :min 2000 :max 2100 :value (.getYear (:start opts))}]]
        [:label "Years " [:input {:type "number" :name "years" :min 1 :max 5 :value (/ (:horizon opts) 12)}]]
        [:label "Fallback fee (%) " [:input {:type "number" :name "fee" :min 0 :max 100 :step "0.01" :value (:fee-percent opts)}]]
        [:label "Fallback fixed fee (in each currency) " [:input {:type "number" :name "fixed" :min 0 :max 10000 :step "0.01" :value (:fee-fixed opts)}]]
        [:details
         [:summary "Model company member cancellations"]
         [:p "Select the first month with no receipts. Leave blank to keep the Stripe subscription schedule. These scenarios do not change Stripe."]
         (for [member (->> rows
                           (filter #(= "company" (:type %)))
                           (map (juxt :id identity))
                           (into {})
                           vals
                           (sort-by :name))]
           [:div [:label (hu/escape-html (:name member)) " "
                  [:input {:type "month" :name (str "cancel-" (:id member)) :id (str "cancel-" (:id member))
                           :aria-label (str "First month without receipts for " (:name member))
                           :min (str (max-key #(.getLong ^YearMonth % java.time.temporal.ChronoField/PROLEPTIC_MONTH)
                                              (first months) (YearMonth/from (.atZone ^Instant now ZoneOffset/UTC))))
                           :max (str (last months))
                           :value (str (:cancel-month member))}]]])]
        [:button {:type "submit"} "Update forecast"]
        [:button {:type "submit" :formaction "/admin/cashflow.xlsx"} "Export Excel"]]
       (when (empty? rows) [:p "No supported subscriptions to forecast."])
       [:p.muted "Expand Membership receipts and click a company's current or future month amount to simulate cancellation from that month onward. Payments already received stay in actuals. Use Reset beside the company name to undo."]
       (for [[currency members] (sort-by key (group-by :currency rows))]
         [:section
          [:h2 (str/upper-case currency)]
          (table forecast [["Baseline net receipts" (cf/total members :baseline (count months))]
                           ["Scenario net receipts" (cf/total members :values (count months))]
                           ["Change" (mapv #(when (and %1 %2) (- %1 %2))
                                           (cf/total members :values (count months))
                                           (cf/total members :baseline (count months)))]] currency
                 (concat [["Developer members" (cf/total (filter #(= "developer" (:type %)) members) :values (count months))]]
                         (map (juxt :name :values identity) (filter #(= "company" (:type %)) members))
                         (when (some #(= "other" (:type %)) members)
                           [["Other subscription receipts" (cf/total (filter #(= "other" (:type %)) members) :values (count months))]])))])
       (when (seq warnings)
         [:section.warning [:h2 "Excluded subscriptions"]
          [:p "These subscriptions are excluded from forecast renewals. Their collected payments are included in actuals. Monthly equivalents include recurring discounts and are before fees."]
          [:div.horizontal-scroll
           [:table
            [:thead [:tr [:th.text-left "Customer"] [:th "Monthly equivalent"]
                     [:th "Billing amount"] [:th.text-left "Due date"]
                     [:th.text-left "Exclusion"]]]
            [:tbody (for [{:keys [name url monthly billing reason]} warnings]
                      [:tr [:td.text-left [:a {:href url :target "_blank" :rel "noopener noreferrer"} (hu/escape-html name)]]
                       [:td (if monthly
                              (str (str/upper-case (:currency monthly)) " "
                                   (format "%,.2f" (double (cf/major (:currency monthly) (:amount monthly)))) "/month")
                              "Unavailable")]
                       [:td (if (some? (:amount billing))
                              (str (str/upper-case (:currency billing)) " "
                                   (format "%,.2f" (double (cf/major (:currency billing) (:amount billing))))) "Unavailable")
                        [:br] [:small.muted (:amount-label billing)]]
                       [:td.text-left
                        (if (:due billing) (str (:due-label billing) " " (:due billing))
                            (or (:due-note billing) "No due date set"))
                        (when (:invoice-date billing) [:div [:small.muted (str "Invoice issued " (:invoice-date billing))]])
                        (when (:next-attempt billing) [:div [:small.muted (str "Next payment attempt " (:next-attempt billing))]])]
                       [:td.text-left (hu/escape-html reason)]])]]]])]])))

(defn handler [db export?]
  (fn [req]
    (try
      (let [now (Instant/now)
            opts (options (:query-params req) now)
            snapshot (stripe/snapshot (:start opts))
            now (:as-of snapshot)
            opts (assoc opts :as-of now :fee-models (cf/fee-models (:fee-history snapshot)))
            members (jdbc/query db ["SELECT id, person_name, organization_name, member_type, stripe_customer_id FROM members"])
            forecast (-> (cf/forecast members (:subscriptions snapshot) (:start opts) (:horizon opts) (:cancellations opts) opts)
                         (cf/with-actuals members (:actuals snapshot)))]
        (if export?
          {:status 200 :headers {"Content-Type" "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
                                 "Content-Disposition" "attachment; filename=clojurists-together-cashflow.xlsx"}
           :body (ByteArrayInputStream. (xlsx/workbook forecast now))}
          {:status 200 :headers {"Content-Type" "text/html; charset=utf-8"}
           :body (page forecast opts now)}))
      (catch clojure.lang.ExceptionInfo e
        (case (:type (ex-data e))
          ::invalid {:status 400 :body (.getMessage e)}
          :org.clojuriststogether.app.stripe/unavailable {:status 503 :body (.getMessage e)}
          :org.clojuriststogether.app.stripe/actuals-unavailable {:status 503 :body (.getMessage e)}
          (throw e)))
      (catch StripeException _
        {:status 503 :body "Stripe could not be loaded. Please retry. No forecast was generated."}))))

(defn routes [db wrap-access]
  [["/admin/cashflow" {:get {:handler (wrap-access (admin/wrap-admin (handler db false) db))}}]
   ["/admin/cashflow.xlsx" {:get {:handler (wrap-access (admin/wrap-admin (handler db true) db))}}]])
