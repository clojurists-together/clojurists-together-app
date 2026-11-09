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

(def cancellation-click
  "var form=document.getElementById('forecast-controls');document.getElementById(this.dataset.input).value=this.dataset.month;document.getElementById('receipts-state').value='open';form.requestSubmit();")

(defn receipt-row [forecast currency [label values member]]
  [:tr [:th.row-label {:scope "row"} (hu/escape-html label)
        (when (:cancel-month member)
          [:div [:small.muted (str "Simulated cancellation: " (:cancel-month member) " ")]
           [:button.receipt-action {:type "button" :data-input (str "cancel-" (:id member))
                                    :data-month "" :onclick cancellation-click
                                    :aria-label (str "Reset cancellation for " label)} "Reset"]])]
   (for [[month value] (map vector (:months forecast) values)]
     [:td {:class (month-class forecast month)}
      (let [amount (if (nil? value) "—" (format "%,.2f" (double (cf/major currency value))))]
        (if (and member (= "Forecast" (cf/month-kind forecast month)))
          [:button.receipt-action {:type "button" :data-input (str "cancel-" (:id member))
                                   :data-month (str month) :onclick cancellation-click
                                   :title (str "Simulate cancellation from " month)
                                   :aria-label (str "Simulate cancellation for " label " from " month "; net receipts " amount " " currency)}
           amount]
          amount))])])

(defn table [forecast rows currency membership-rows]
  [:div {:style "overflow-x:auto"}
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
        {:type "button" :aria-expanded (str (boolean (get-in forecast [:options :receipts-open]))) :aria-controls (str "membership-" currency)
         :onclick "var rows=document.getElementById(this.getAttribute('aria-controls'));var expanded=this.getAttribute('aria-expanded')==='true';rows.hidden=expanded;this.setAttribute('aria-expanded',String(!expanded));document.getElementById('receipts-state').value=expanded?'':'open';"}
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
        [:style "body{font:16px system-ui;color:#182d36;margin:2rem auto;padding:0 1.5rem;max-width:1500px}a{color:#136b60}h1{margin-bottom:.5rem}p{max-width:85ch;line-height:1.5}table{border-collapse:separate;border-spacing:0;width:100%;margin:1rem 0;font-variant-numeric:tabular-nums}th,td{padding:.65rem;border-bottom:1px solid #edf0f1;white-space:nowrap;text-align:right}.row-label{text-align:left;min-width:220px;position:sticky;left:0;background:white;z-index:1}thead .row-label{background:white}.year-heading{text-align:left;background:white;border-left:1px solid #bac5c9;font-size:1rem;font-weight:600;letter-spacing:.03em}thead .month-past{background:#f7f8f9;color:#52616a}thead .month-future{background:#f4f8f6;color:#36554b}thead .month-current{background:#faf6eb;color:#66542c;border-top:2px solid #b7a476}tbody .month-current{font-weight:500}thead small{font-weight:500;font-size:.75rem}thead th:not(.row-label):not(.year-heading){min-width:105px}.year-start{border-left:1px solid #bac5c9}label{display:inline-block;margin:.5rem 1rem .5rem 0}input{font:inherit;padding:.35rem;border:1px solid #9baba9;border-radius:4px}button,.export{display:inline-block;background:#136b60;color:white;border:0;border-radius:4px;padding:.65rem 1rem;font:inherit;margin:.5rem 1rem .5rem 0}summary{cursor:pointer;font-weight:600;padding:.6rem 0}.muted{color:#586b72}.warning{border-top:1px solid #d6dde0;padding-top:1rem}section{margin-top:2rem}button.membership-toggle{background:none;color:inherit;padding:0;margin:0;font-weight:600;cursor:pointer}button.membership-toggle::before{content:'▸';display:inline-block;width:1em;text-align:left}button.membership-toggle[aria-expanded=true]::before{content:'▾'}.membership-details[hidden]{display:none}.membership-details th{font-weight:400}.membership-details tr:first-child th{font-weight:600}button.receipt-action{background:none;color:inherit;border-radius:2px;padding:0;margin:0;cursor:pointer;font:inherit}button.receipt-action:hover{text-decoration:underline;color:#136b60}button.receipt-action:focus-visible{outline:2px solid #136b60;outline-offset:3px}.row-label button.receipt-action{font-size:.8rem;color:#136b60}"]]
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
           [:div {:style "overflow-x:auto"}
            [:table
             [:thead [:tr [:th {:style "text-align:left"} "Customer"] [:th "Monthly equivalent"]
                      [:th "Billing amount"] [:th {:style "text-align:left"} "Due date"]
                      [:th {:style "text-align:left"} "Exclusion"]]]
             [:tbody (for [{:keys [name url monthly billing reason]} warnings]
                  [:tr [:td {:style "text-align:left"} [:a {:href url :target "_blank" :rel "noopener noreferrer"} (hu/escape-html name)]]
                   [:td (if monthly
                              (str (str/upper-case (:currency monthly)) " "
                                   (format "%,.2f" (double (cf/major (:currency monthly) (:amount monthly)))) "/month")
                              "Unavailable")]
                   [:td (if (some? (:amount billing))
                          (str (str/upper-case (:currency billing)) " "
                               (format "%,.2f" (double (cf/major (:currency billing) (:amount billing))))) "Unavailable")
                    [:br] [:small.muted (:amount-label billing)]]
                   [:td {:style "text-align:left"}
                    (if (:due billing) (str (:due-label billing) " " (:due billing))
                      (or (:due-note billing) "No due date set"))
                    (when (:invoice-date billing) [:div [:small.muted (str "Invoice issued " (:invoice-date billing))]])
                    (when (:next-attempt billing) [:div [:small.muted (str "Next payment attempt " (:next-attempt billing))]])]
                   [:td {:style "text-align:left"} (hu/escape-html reason)]])]]]])]])))

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
          :org.clojuriststogether.app.stripe/actuals-unavailable {:status 503 :body (.getMessage e)}
          (throw e)))
      (catch StripeException _
        {:status 503 :body "Stripe could not be loaded. Please retry. No forecast was generated."}))))

(defn routes [db]
  [["/admin/cashflow" {:get {:handler (admin/wrap-admin (handler db false) db)}}]
   ["/admin/cashflow.xlsx" {:get {:handler (admin/wrap-admin (handler db true) db)}}]])
