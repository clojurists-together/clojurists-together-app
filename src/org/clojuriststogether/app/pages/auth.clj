(ns org.clojuriststogether.app.pages.auth
  (:require [org.clojuriststogether.app.template :as template]
            [ring.util.http-response :as response]
            [ring.util.anti-forgery :as anti-forgery]
            [org.clojuriststogether.app.utils :as utils]
            [org.clojuriststogether.app.spec :as specs]
            [org.clojuriststogether.app.email :as email]
            [honeysql.core :as sql]
            [honeysql.helpers :as hh]
            [spec-tools.data-spec :as ds]
            [clojure.string :as str]
            [clojure.java.jdbc :as jdbc]
            [org.clojuriststogether.app.sessions :as sessions]
            [clojure.spec.alpha :as s])
  (:import (com.stripe.model Plan Product Customer)

           com.stripe.model.checkout.Session
           (com.stripe.exception InvalidRequestException)
           (java.util UUID)
           (java.time Instant)))

(def sample-email "john@clojurelover.com")
(def link-blue ["inline-block" "align-baseline" "font-bold" "text-blue-500" "hover:text-blue-800"])

(def developer-member [:a {:class link-blue
                           :href "https://www.clojuriststogether.org/developers/"} "Developer"])
(def company-member [:a {:class link-blue
                         :href "https://www.clojuriststogether.org/companies/"} "Company"])

(defn login-page [req]
  (template/template req
    [:form.bg-white.shadow-md.rounded.px-8.pt-6.pb-8.mb-4
     {:method "POST"}
     [:div.mb-4
      [:label.block.text-gray-700.text-sm.font-bold.mb-2 {:for "email"} "Email"]
      [:input#username.shadow.appearance-none.border.rounded.w-full.py-2.px-3.text-gray-700.leading-tight.focus:outline-none.focus:shadow-outline
       {:type "email" :name "email" :placeholder sample-email :required true}]]
     (anti-forgery/anti-forgery-field)
     [:div.flex.items-center.justify-between
      [:button.bg-blue-500.hover:bg-blue-700.text-white.font-bold.py-2.px-4.rounded.focus:outline-none.focus:shadow-outline "Sign In"]
      #_[:a.inline-block.align-baseline.font-bold.text-sm.text-blue-500.hover:text-blue-800 {:href "#"} "Forgot Password?"]]
     [:p.mt-4.text-sm "Not a member yet? Sign up as a " developer-member " or " company-member " member."]
     ]

    ))

(defn checkout-response [req session stripe]
  (-> (template/template req
        [:div [:h1 "Loading checkout"]
         [:script (format "var checkout = '%s'" (.getId session))]
         [:script
          ;; SECURITY TODO: do this more securely
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
                                             "customer" customer-id
                                             "subscription_data" {"items" {"0" {"plan" (.getId plan)}}}
                                             "success_url" "https://www.clojuriststogether.org/signup-success/"
                                             ;; TODO: better URLs
                                             "cancel_url" "https://www.clojuriststogether.org/signup-success/"}))

(defn ^com.stripe.model.billingportal.Session self-serve-session [customer-id]
  (com.stripe.model.billingportal.Session/create
    {"customer" customer-id}))

(defn member-by-email [db email]
  (->> {:select [:*]
        :from [:members]
        :where [:= :email email]}
       (sql/format)
       (jdbc/query db)
       first))

(defn member-exists? [db email]
  (boolean (member-by-email db email)))

(defn get-member [db id]
  (->> {:select [:*]
        :from [:members]
        :where [:= :id id]}
       (sql/format)
       (jdbc/query db)
       first))

(defn retrieve-plan [plan-id]
  (try (Plan/retrieve plan-id)
       (catch InvalidRequestException e
         (throw (ex-info "Plan not found" {:type :reitit.ring/response
                                           :response {:status 422
                                                      :body "<h1>Plan not found</h1>"}})))))

(defn already-a-member-ex [email]
  (ex-info "Email is already a member" {:type :reitit.ring/response
                                        :response {:status 422
                                                   :body (format "<h1>Email %s is already a member. Try logging in.</h1>" email)}}))

(def retrieve-plan-memo (memoize retrieve-plan))

(defn retrieve-product [product-id]
  (try (Product/retrieve product-id)
       (catch InvalidRequestException e
         (throw (ex-info "Product not found" {:type :reitit.ring/response
                                              :response {:status 422
                                                         :body "<h1>Product not found</h1>"}})))))

(def retrieve-product-memo (memoize retrieve-product))

(defn unauthenticated!
  "This is an ugly hack until I have middleware to redirect logged in users to manage page."
  [req]
  (when (sessions/member-id req)
    (response/throw! {:status 302
                      :headers {"Location" (utils/route-name->path req :manage)}
                      :body ""})))

(defn auth-routes [stripe db email-service]
  [["/login" {:name :login
              :get {:handler (fn [req]
                               (unauthenticated! req)
                               (-> (response/ok (login-page req))
                                   (response/content-type "text/html")))}
              :post {:parameters {:form {:email ::specs/email}}
                     :handler
                     (fn [req]
                       (let [email (get-in req [:form-params "email"])
                             member-id (:id (member-by-email db email))]
                         (-> (if member-id
                               (let [login-id (UUID/randomUUID)]
                                 (->> {:insert-into :login_links
                                       :values [{:id login-id
                                                 :member_id member-id}]}
                                      sql/format
                                      (jdbc/execute! db))
                                 (email/send email-service {:to "desk@danielcompton.net"
                                                            :from "hi@clojuriststogether.org"
                                                            :subject "Log in to Clojurists Together"
                                                            :body (format "<p><a clicktracking=off href='%s'>Click here to log in</a>"
                                                                          (str "https://members.clojuriststogether.org/magic?token=" login-id))
                                                            :content-type "text/html"})
                                 (template/template req
                                   [:h1 "Login success"]
                                   [:p "Check your email for a link to manage your subscription"]))
                               (template/template req
                                 [:h1 "Login failed"]
                                 [:p "No membership for this email. Sign up for a " developer-member " or " company-member " membership"]))
                             (response/ok)
                             (response/content-type "text/html"))))}}]
   ["/logout" {:name :logout
               :post {:handler (fn [req]
                                 (-> (response/found (utils/route-name->path req :home))
                                     (assoc :session nil)))}}]
   ["/magic" {:name :magic-link
              :get {:parameters {:query {:token string?}}
                    :handler (fn [req]
                               (let [token (get-in req [:parameters :query :token])
                                     token-uuid (try (UUID/fromString token) (catch IllegalArgumentException e nil))
                                     now (Instant/now)
                                     ;; TODO: store selector and verifier to prevent timing attacks?
                                     login-link (when token-uuid
                                                  ;; Don't even try to query if we can't parse the token
                                                  (->> {:select [:member_id :created_at :expires_at]
                                                        :from [:login_links]
                                                        :where [:= :id token-uuid]}
                                                       (sql/format)
                                                       (jdbc/query db)
                                                       first))]
                                 ;; TODO: clean up old login links 24 hours after they expired
                                 (cond
                                   (and login-link (.isBefore now (:expires_at login-link)))
                                   (do
                                     (->> {:delete-from :login_links
                                           :where [:= :id token-uuid]}
                                          sql/format
                                          (jdbc/execute! db))
                                     (-> (response/found (utils/route-name->path req :manage))
                                         (assoc-in [:session :member-id] (:member_id login-link))
                                         ;; TODO: do we need to recreate the session here?
                                         #_(update :session vary-meta assoc :recreate true)))

                                   (and login-link (not (.isBefore now (:expires_at login-link))))
                                   (-> (template/template req
                                         [:h1 "Link expired"]
                                         [:p "Your login link has expired. Please "
                                          [:a {:href (utils/login-path req) :class link-blue} "login"] " again."])
                                       (response/forbidden)
                                       (response/content-type "text/html"))

                                   :else
                                   (-> (template/template req
                                         [:h1 "Link not found"]
                                         [:p "Couldn't find this login link. Please "
                                          [:a {:href (utils/login-path req) :class link-blue} "login"] " again."])
                                       (response/forbidden)
                                       (response/content-type "text/html")))))}}]
   ["/manage" {:name :manage
               :get {:handler (fn [req]
                                ;; TODO: proper authn/authz middleware
                                (if-let [member (some->> (sessions/member-id req)
                                                         (get-member db))]
                                  (-> (template/template req
                                        [:h1 {:class "block text-xl mb-2"} "Manage your account"]
                                        #_[:h2 (pr-str member)]
                                        [:p "Email: " (:email member)]
                                        [:p "Name: " (:person_name member)]
                                        [:h2 {:class "block text-lg mb-2 mt-4"} "Billing"]
                                        [:form {:method "POST" :action (utils/route-name->path req :manage-billing)}
                                         (anti-forgery/anti-forgery-field)
                                         [:button {:class link-blue} "Manage billing details"]])
                                      (response/ok)
                                      (response/content-type "text/html"))
                                  (response/found (utils/login-path req))))}}]
   ["/manage/billing" {:name :manage-billing
                       :post {:handler (fn [req]
                                         (if-let [member-id (sessions/member-id req)]
                                           (let [customer-id (:stripe_customer_id (get-member db member-id))
                                                 session (self-serve-session customer-id)]
                                             (response/found (.getUrl session)))
                                           (response/found (utils/login-path req))))}}]
   ["/register/developer"
    {:name :register-developer
     :get {:parameters {:query {:plan ::specs/plan-id}}
           :handler (fn [req]
                      ;; TODO: use :parameters
                      (unauthenticated! req)
                      (let [plan-id (get-in req [:query-params "plan"])
                            plan (retrieve-plan-memo plan-id)
                            product (retrieve-product-memo (.getProduct plan))]
                        (-> (template/template
                              req
                              [:form.bg-white.shadow-md.rounded.px-8.pt-6.pb-8.mb-4
                               {:method "POST"}
                               ;; TODO: copy values from here into the Full Name form
                               [:div.mb-4
                                [:label.block.text-gray-700.text-sm.font-bold.mb-2 {:for "preferred-name"} "Preferred Name"]
                                [:input#username.shadow.appearance-none.border.rounded.w-full.py-2.px-3.text-gray-700.leading-tight.focus:outline-none.focus:shadow-outline
                                 {:name "preferred-name" :type "text" :placeholder "John" :required true :autocomplete "name"}]]
                               [:div.mb-4
                                [:label.block.text-gray-700.text-sm.font-bold.mb-2 {:for "name"} "Full Name"]
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
                                [:button.bg-blue-500.hover:bg-blue-700.text-white.font-bold.py-2.px-4.rounded.focus:outline-none.focus:shadow-outline "Sign Up"]]])
                            (response/ok)
                            (response/content-type "text/html"))))}
     :post {:parameters {:form {:name string?
                                :email ::specs/email
                                :plan ::specs/plan-id}}
            :handler (fn [req]
                       ;; TODO: use a transaction!!
                       (let [{:strs [email name preferred-name]} (get req :form-params)
                             ;; Check if user exists
                             ;; Create member if they don't exist
                             exists? (member-exists? db email)
                             _ (when exists?
                                 ;; TODO: send them to login page
                                 ;; If they do, send them to the page to update membership details
                                 (throw (already-a-member-ex email)))
                             customer (Customer/create {"email" email
                                                        "name" name})
                             customer-id (.getId customer)
                             member (->> {:insert-into :members
                                          :values [{:email email
                                                    :person_name name
                                                    :preferred_name preferred-name
                                                    :member_type "developer"
                                                    :stripe_customer_id customer-id}]
                                          :returning [:id]}
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
                         (-> (checkout-response req session stripe)
                             (assoc-in [:session :member-id] (first member)))))}}]
   ["/register/company"
    {:name :register-company
     :get {:parameters
           {:query {:plan ::specs/plan-id}}
           :handler
           (fn [req]
             (unauthenticated! req)
             (let [plan-id (get-in req [:query-params "plan"])
                   plan (retrieve-plan-memo plan-id)
                   product (retrieve-product-memo (.getProduct plan))]
               (-> (template/template
                     req
                     [:form.bg-white.shadow-md.rounded.px-8.pt-6.pb-8.mb-4
                      {:method "POST"}
                      [:div.mb-2
                       [:span.block.text-gray-700.text-md.font-bold.mb-2
                        "Primary Contact Details"]]
                      [:div.mb-4
                       [:label.block.text-gray-700.text-sm.font-bold.mb-2 {:for "preferred-name"} "Preferred Name"]
                       [:input#username.shadow.appearance-none.border.rounded.w-full.py-2.px-3.text-gray-700.leading-tight.focus:outline-none.focus:shadow-outline
                        {:name "preferred-name" :type "text" :placeholder "John" :required true :autocomplete "name"}]]
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
                       [:button.bg-blue-500.hover:bg-blue-700.text-white.font-bold.py-2.px-4.rounded.focus:outline-none.focus:shadow-outline "Sign Up"]]])
                   (response/ok)
                   (response/content-type "text/html"))))}
     :post {:parameters
            {:form {:name string?
                    :email ::specs/email
                    :org-name string?
                    :org-url string?
                    :plan ::specs/plan-id
                    (ds/opt :invoicing-email) (s/or :empty str/blank?
                                                    :email ::specs/email)
                    (ds/opt :updates-email) (s/or :empty str/blank?
                                                  :email ::specs/email)}}
            :handler
            (fn [req]
              ;; TODO: use a transaction!!
              (let [{:strs [email name org-name org-url invoicing-email updates-email preferred-name]}
                    (get req :form-params)
                    ;; Check if user exists
                    ;; Create member if they don't exist
                    exists? (member-exists? db email)
                    _ (when exists?
                        ;; TODO: send them to login page
                        ;; If they do, send them to the page to update membership details
                        (throw (already-a-member-ex email)))
                    invoicing-email (if (str/blank? invoicing-email) nil invoicing-email)
                    updates-email (if (str/blank? updates-email) nil updates-email)
                    customer (Customer/create {"email" email
                                               "name" org-name
                                               ;; TODO: work out how to set the invoicing email via API
                                               #_#_"invoicing" {"email_to" invoicing-email}})
                    customer-id (.getId customer)
                    member (->> {:insert-into :members
                                 :values [{:email email
                                           :preferred_name preferred-name
                                           :person_name name
                                           :member_type "company"
                                           :stripe_customer_id customer-id
                                           :organization_name org-name
                                           :organization_url org-url
                                           :invoicing-email invoicing-email
                                           :updates-email updates-email}]
                                 :returning [:id]}
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
                (-> (checkout-response req session stripe)
                    (assoc-in [:session :member-id] (first member)))))}}]])
