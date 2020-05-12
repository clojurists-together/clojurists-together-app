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

(defn checkout-response [req session stripe]
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
            (:publishable-key stripe))]])
      (response/ok)
      (response/content-type "text/html")))

(defn checkout-session [customer-id plan]
  (com.stripe.model.checkout.Session/create {"payment_method_types" ["card"]
                                             "customer"             customer-id
                                             "subscription_data"    {"items" {"0" {"plan" (.getId plan)}}}
                                             "success_url"          "https://www.clojuriststogether.org/signup-success/"
                                             ;; TODO: better URLs
                                             "cancel_url"           "https://www.clojuriststogether.org/signup-success/"}))

(defn member-exists? [db email]
  (->> {:select [:email]
        :from   [:members]
        :where  [:= :email email]}
       (sql/format)
       (jdbc/query db)
       seq
       boolean))

(defn retrieve-plan [plan-id]
  (Plan/retrieve plan-id))

(def retrieve-plan-memo (memoize retrieve-plan))

(defn retrieve-product [product-id]
  (Product/retrieve product-id))

(def retrieve-product-memo (memoize retrieve-product))

(defn auth-routes [stripe db]
  [["/login" {:name :login
              :get  {:handler (fn [req]
                                (-> (response/ok (login-page req))
                                    (response/content-type "text/html")))}
              :post {:parameters {:query {:email string?}}
                     :handler
                                 (fn [req]
                                   (prn "Start")
                                   (let [email (get-in req [:form-params "email"])
                                         customer-id (->> {:select [:stripe_customer_id]
                                                           :from   [:members]
                                                           :where  [:= :email email]}
                                                          (sql/format)
                                                          (jdbc/query db)
                                                          first
                                                          :stripe_customer_id)]
                                     (prn "Customer ID" customer-id email)
                                     (if customer-id
                                       (let [_ (prn "got it")
                                             self-serve-session (com.stripe.model.billingportal.Session/create
                                                                  {"customer" customer-id})]
                                         (-> (response/ok (template/template req
                                                            [:h1 "Login success"]
                                                            [:p "Check your email for a link to manage your subscription"]
                                                            [:a {:href (.getUrl self-serve-session)} "Manage Your Subscription"]

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
     :get  {:parameters {:query {:plan string?}}
            :handler    (fn [req]
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
                                      [:input#username.shadow.appearance-none.border.rounded.w-full.py-2.px-3.text-gray-700.leading-tight.focus:outline-none.focus:shadow-outline
                                       {:name "name" :type "text" :placeholder "John Smith" :required true :autocomplete "name"}]]
                                     [:div.mb-4
                                      [:label.block.text-gray-700.text-sm.font-bold.mb-2 {:for "email"} "Email Address"]
                                      [:input#username.shadow.appearance-none.border.rounded.w-full.py-2.px-3.text-gray-700.leading-tight.focus:outline-none.focus:shadow-outline
                                       {:name "email" :type "email" :placeholder sample-email :required true}]]
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
     :post {:parameters {:form {:name  string?
                                :email string?}}
            :handler    (fn [req]
                          (try
                            ;; TODO: use a transaction!!
                            (let [
                                  {:strs [email name]} (get req :form-params)
                                  ;; Check if user exists
                                  ;; Create member if they don't exist
                                  exists? (member-exists? db email)
                                  _ (when exists?
                                      ;; TODO: send them to login page
                                      ;; If they do, send them to the page to update membership details
                                      (throw (ex-info "Already a member" {:email email})))
                                  customer (Customer/create {"email" email
                                                             "name"  name})
                                  customer-id (.getId customer)
                                  member (->> {:insert-into :members
                                               :values      [{:email              email
                                                              :person_name        name
                                                              :member_type        "developer"
                                                              :stripe_customer_id customer-id}]}
                                              (sql/format)
                                              (jdbc/execute! db))

                                  ;; Create Checkout session
                                  plan-id (get-in req [:form-params "plan"])
                                  plan (retrieve-plan-memo plan-id)
                                  session (checkout-session customer-id plan)]

                              ;; Later:
                              ;; Add them to Mailchimp
                              ;; Add them to the website
                              ;; Add to Google docs
                              (checkout-response req session stripe))
                            (catch Exception e
                              (prn e)
                              (throw e))))}}]
   ["/register/company"
    {:name :register-company
     :get  {:parameters
            {:query {:plan string?}}
            :handler
            (fn [req]
              (let [plan-id (get-in req [:query-params "plan"])
                    plan (retrieve-plan-memo plan-id)
                    product (retrieve-product-memo (.getProduct plan))]
                (-> (template/template
                      req
                      [:div.bg-white.rounded-t-lg.overflow-hidden.border-t.border-l.border-r.border-gray-400.p-4.px-3.py-10.bg-gray-200.flex.justify-center
                       [:div.w-full.max-w-sm
                        [:form.bg-white.shadow-md.rounded.px-8.pt-6.pb-8.mb-4
                         {:method "POST"}
                         [:div.mb-2
                          [:span.block.text-gray-700.text-md.font-bold.mb-2
                           "Primary Contact Details"]]
                         [:div.mb-4
                          [:label.block.text-gray-700.text-sm.font-bold.mb-2 {:for "name"} "Name"]
                          [:input#username.shadow.appearance-none.border.rounded.w-full.py-2.px-3.text-gray-700.leading-tight.focus:outline-none.focus:shadow-outline
                           {:name "name" :type "text" :placeholder "John Smith" :required true :autocomplete "name"}]]
                         [:div.mb-4
                          [:label.block.text-gray-700.text-sm.font-bold.mb-2 {:for "email"} "Email Address"]
                          [:input#username.shadow.appearance-none.border.rounded.w-full.py-2.px-3.text-gray-700.leading-tight.focus:outline-none.focus:shadow-outline
                           {:name "email" :type "email" :placeholder "john@acme.com" :required true}]]
                         [:div.mb-2
                          [:span.block.text-gray-700.text-md.font-bold.mb-2
                           "Organization Details"]]
                         [:div.mb-4
                          [:label.block.text-gray-700.text-sm.font-bold.mb-2 {:for "org-name"} "Organization Name"]
                          [:input#username.shadow.appearance-none.border.rounded.w-full.py-2.px-3.text-gray-700.leading-tight.focus:outline-none.focus:shadow-outline
                           {:name "org-name" :type "text" :placeholder "Acme" :autocomplete "organization" :required true}]]
                         [:div.mb-4
                          [:label.block.text-gray-700.text-sm.font-bold.mb-2 {:for "org-url"} "URL"]
                          [:input#username.shadow.appearance-none.border.rounded.w-full.py-2.px-3.text-gray-700.leading-tight.focus:outline-none.focus:shadow-outline
                           {:name "org-url" :type "url" :placeholder "https://www.acme.com" :required true}]]
                         [:div.mb-4
                          [:label.block.text-gray-700.text-sm.font-bold.mb-2 {:for "invoicing-email"} "Invoicing Email"
                           [:span.text-gray-600.text-sm.ml-2 "Optional"]]
                          [:p.text-gray-700.text-sm "Email to send invoices to. If not provided, will be sent to the primary contact."]
                          [:input#username.shadow.appearance-none.border.rounded.w-full.py-2.px-3.text-gray-700.leading-tight.focus:outline-none.focus:shadow-outline
                           {:name "invoicing-email" :type "email" :placeholder "accounts@acme.com"}]]
                         [:div.mb-4
                          [:label.block.text-gray-700.text-sm.font-bold.mb-2 {:for "updates-email"} "News and Surveys email"
                           [:span.text-gray-600.text-sm.ml-2 "Optional"]]
                          [:p.text-gray-700.text-sm "Email to send news updates and surveys to. If not provided, will be sent to the primary contact."]
                          [:input#username.shadow.appearance-none.border.rounded.w-full.py-2.px-3.text-gray-700.leading-tight.focus:outline-none.focus:shadow-outline
                           {:name "updates-email" :type "email" :placeholder "dev-team@acme.com"}]]
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
     :post {:parameter
            {:form {:name                     string?
                    :email                    string?
                    :org-name                 string?
                    :org-url                  string?
                    (ds/opt :invoicing-email) string?
                    (ds/opt :updates-email)   string?}}
            :handler
            (fn [req]
              (try
                ;; TODO: use a transaction!!
                (let [{:strs [email name org-name org-url invoicing-email updates-email]} (get req :form-params)
                      ;; Check if user exists
                      ;; Create member if they don't exist
                      exists? (member-exists? db email)
                      _ (when exists?
                          ;; TODO: send them to login page
                          ;; If they do, send them to the page to update membership details
                          (throw (ex-info "Already a member" {:email email})))
                      customer (Customer/create {"email" email
                                                 "name"  org-name
                                                 ;; TODO: work out how to set the invoicing email via API
                                                 #_ #_ "invoicing" {"email_to" invoicing-email}})
                      customer-id (.getId customer)
                      member (->> {:insert-into :members
                                   :values      [{:email              email
                                                  :person_name        name
                                                  :member_type        "company"
                                                  :stripe_customer_id customer-id
                                                  :organization_name org-name
                                                  :organization_url org-url
                                                  :invoicing-email invoicing-email
                                                  :updates-email updates-email

                                                  }]}
                                  (sql/format)
                                  (jdbc/execute! db))

                      ;; Create Checkout session
                      plan-id (get-in req [:form-params "plan"])
                      plan (retrieve-plan-memo plan-id)
                      session (checkout-session customer-id plan)]

                  ;; Later:
                  ;; Add them to Mailchimp
                  ;; Add them to the website
                  ;; Add to Google docs
                  (checkout-response req session stripe))
                (catch Exception e
                  (prn e)
                  (throw e))))}}]])
