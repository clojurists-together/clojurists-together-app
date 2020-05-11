(ns org.clojuriststogether.app.pages.auth
  (:require [org.clojuriststogether.app.template :as template]
            [ring.util.http-response :as response]
            [ring.util.anti-forgery :as anti-forgery]
            [honeysql.core :as sql]
            [honeysql.helpers :as hh]
            [spec-tools.data-spec :as ds]
            [clojure.string :as str]
            [clojure.java.jdbc :as jdbc])
  (:import (com.stripe.model Plan Product Customer)

           com.stripe.model.checkout.Session))

(def sample-email "john@clojurelover.com")

(defn login-page [req]
  (template/template req
                     [:div.bg-white.rounded-t-lg.overflow-hidden.border-t.border-l.border-r.border-gray-400.p-4.px-3.py-10.bg-gray-200.flex.justify-center
                      [:div.w-full.max-w-xs
                       [:form.bg-white.shadow-md.rounded.px-8.pt-6.pb-8.mb-4
                        {:method "POST"}
                        [:div.mb-4
                         [:label.block.text-gray-700.text-sm.font-bold.mb-2 {:for "email"} "Email"]
                         [:input#username.shadow.appearance-none.border.rounded.w-full.py-2.px-3.text-gray-700.leading-tight.focus:outline-none.focus:shadow-outline {:type "text" :name "email" :placeholder sample-email}]]
                        [:div.flex.items-center.justify-between
                         [:button.bg-blue-500.hover:bg-blue-700.text-white.font-bold.py-2.px-4.rounded.focus:outline-none.focus:shadow-outline "Sign In"]
                         [:a.inline-block.align-baseline.font-bold.text-sm.text-blue-500.hover:text-blue-800 {:href "#"} "Forgot Password?"]]]]]))


(defn retrieve-plan [plan-id]
  (Plan/retrieve plan-id))

(def retrieve-plan-memo (memoize retrieve-plan))

(defn retrieve-product [product-id]
  (Product/retrieve product-id))

(def retrieve-product-memo (memoize retrieve-product))

(defn auth-routes [stripe db]
  [["/login" {:name :login
              :get {:handler (fn [req]
                               (-> (response/ok (login-page req))
                                   (response/content-type "text/html")))}
              :post {:parameters {:query {:email string?}}
                     :handler
                     (fn [req]
                       (prn "Start")
                       (let [email (get-in req [:form-params "email"])
                             customer-id (->> {:select [:stripe_customer_id]
                                               :from [:members]
                                               :where [:= :email email]}
                                              (sql/format)
                                              (jdbc/query db)
                                              first
                                              :stripe_customer_id)]
                         (prn "Customer ID" customer-id email)
                         (if customer-id
                           (let [self-serve-session (com.stripe.model.billingportal.Session/create
                                                      {"customer" customer-id})]
                             (prn self-serve-session)
                             (-> (response/ok (template/template req
                                                                 [:h1 "Login success"]
                                                                 [:p "Check your email for a link to manage your subscription"]
                                                                 [:p (pr-str self-serve-session)]

                                                                 ))
                                 (response/content-type "text/html")))
                           (-> (response/ok (template/template req
                                                               [:h1 "Login failed"]
                                                               ;; TODO: include URLs here
                                                               [:p "No membership for this email. Sign up for a Company or Developer membership"]))
                               (response/content-type "text/html"))))

                       )}}]
   ["/register/developer"
    {:name :register-developer
     :get {:parameters {:query {(ds/opt :plan) string?}}
           :handler (fn [req]
                      ;; TODO: use :parameters
                      (let [plan-id (get-in req [:query-params "plan"])
                            plan (retrieve-plan-memo plan-id)
                            product (retrieve-product-memo (.getProduct plan))]
                        (-> (template/template
                              req
                              [:div.bg-white.rounded-t-lg.overflow-hidden.border-t.border-l.border-r.border-gray-400.p-4.px-3.py-10.bg-gray-200.flex.justify-center
                               [:div.w-full.max-w-xs
                                [:form.bg-white.shadow-md.rounded.px-8.pt-6.pb-8.mb-4
                                 {:method "POST"}
                                 [:div.mb-4
                                  [:label.block.text-gray-700.text-sm.font-bold.mb-2 {:for "name"} "Name"]
                                  [:input#username.shadow.appearance-none.border.rounded.w-full.py-2.px-3.text-gray-700.leading-tight.focus:outline-none.focus:shadow-outline {:name "name" :type "text" :placeholder "John Smith" :required true :autocomplete "name"}]]
                                 [:div.mb-4
                                  [:label.block.text-gray-700.text-sm.font-bold.mb-2 {:for "email"} "Email Address"]
                                  [:input#username.shadow.appearance-none.border.rounded.w-full.py-2.px-3.text-gray-700.leading-tight.focus:outline-none.focus:shadow-outline {:name "email" :type "email" :placeholder sample-email :required true}]]
                                 [:input {:type "hidden" :name "plan" :value plan-id}]
                                 (anti-forgery/anti-forgery-field)
                                 [:div.mb-4
                                  [:span.block.text-gray-700.text-sm.font-bold.mb-2 "Plan"]
                                  [:p (.getName product) " - " (.getNickname plan)]
                                  [:p (/ (.getAmountDecimal plan) 100) " " (str/upper-case (.getCurrency plan))
                                   " every " (.getInterval plan)]
                                  ;[:p "Plan " plan]
                                  ;[:p "Product " product]
                                  ]
                                 [:div.mb-4
                                  [:p "You'll enter your card details on the next screen"]]

                                 [:div.flex.items-center.justify-between
                                  [:button.bg-blue-500.hover:bg-blue-700.text-white.font-bold.py-2.px-4.rounded.focus:outline-none.focus:shadow-outline "Sign Up"]]]]])
                            (response/ok)
                            (response/content-type "text/html"))))}
     :post {:parameters {:form {:name string?
                                :email string?}}
            :handler (fn [req]
                       (try
                         ;; TODO: use a transaction!!

                         (let [;; Check if user exists
                               email (get-in req [:form-params "email"])
                               name (get-in req [:form-params "name"])

                               ;; If they do, send them to the page to update membership details
                               ;; Create member if they don't exist
                               exists? (->> {:select [:email]
                                             :from [:members]
                                             :where [:= :email email]}
                                            (sql/format)
                                            (jdbc/query db))
                               _ (when (seq exists?)
                                   (throw (ex-info "Already a member" {:email email})))
                               customer (Customer/create {"email" email
                                                          "name" name})
                               member (->> {:insert-into :members
                                            :values [{:email email
                                                      :person_name name
                                                      :member_type "developer"
                                                      :stripe_customer_id (.getId customer)}]}
                                           (sql/format)
                                           (jdbc/execute! db))

                               ;; Create Checkout session
                               plan-id (get-in req [:form-params "plan"])

                               plan (retrieve-plan-memo plan-id)
                               session (com.stripe.model.checkout.Session/create {"payment_method_types" ["card"]
                                                                                  "customer_email" email
                                                                                  "subscription_data" {"items" {"0" {"plan" (.getId plan)}}}
                                                                                  "success_url" "https://www.clojuriststogether.org/signup-success/"
                                                                                  ;; TODO: better URLs
                                                                                  "cancel_url" "https://www.clojuriststogether.org/signup-success/"})
                               ]

                           ;; Check if the user exists
                           ;; If not then create them and create a Stripe customer
                           ;; Create a Checkout session

                           ;; Later:
                           ;; Add them to Mailchimp
                           ;; Add them to the website
                           ;; Add to Google docs
                           (-> (template/template req
                                                  [:div [:h1 "Loading checkout"]
                                                   [:script (format "var checkout = '%s'" (.getId session))]
                                                   [:script
                                                    (format
                                                      "var stripe = Stripe('%s');

                                                      stripe.redirectToCheckout({
                                                        sessionId: checkout}).then(function (result) {
                                                          // TODO: Handle errors
                                                        });"
                                                      (:publishable-key stripe))
                                                    ]
                                                   ])
                               (response/ok)
                               (response/content-type "text/html")))
                         (catch Exception e
                           (prn e)
                           (throw e))))}}]
   ["/register/company" {:name :register-company
                         :get {:handler (fn [req]
                                          )}}]])
