(ns org.clojuriststogether.app.stripe-billing-test
  (:require [cheshire.core :as json]
            [clojure.java.jdbc :as jdbc]
            [clojure.spec.alpha :as s]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [hiccup2.core :as h]
            [integrant.core :as ig]
            [reitit.core :as r]
            [ring.middleware.session.memory :as memory]
            [org.clojuriststogether.app.admin.batch-fill :as batch-fill]
            [org.clojuriststogether.app.pages.auth :as auth]
            [org.clojuriststogether.app.spec :as specs]
            [org.clojuriststogether.app.routes])
  (:import (com.stripe Stripe)
           (com.sun.net.httpserver HttpHandler HttpServer)
           (java.net InetSocketAddress URLDecoder)))

(defn decode-form [body]
  (into {}
        (for [pair (str/split body #"&")
              :when (seq pair)
              :let [[k v] (str/split pair #"=" 2)]]
          [(URLDecoder/decode k "UTF-8")
           (URLDecoder/decode (or v "") "UTF-8")])))

(defn with-stripe-server
  "Runs the real SDK against local HTTP and captures decoded requests."
  [respond run]
  (let [requests (atom [])
        server (HttpServer/create (InetSocketAddress. "127.0.0.1" 0) 0)
        old-base (Stripe/getApiBase)
        old-key Stripe/apiKey
        old-telemetry Stripe/enableTelemetry]
    (.createContext server "/"
                    (reify HttpHandler
                      (handle [_ exchange]
                        (let [uri (.getRequestURI exchange)
                              request {:method (.getRequestMethod exchange)
                                       :path (.getPath uri)
                                       :query (decode-form (or (.getRawQuery uri) ""))
                                       :params (decode-form (slurp (.getRequestBody exchange)))
                                       :api-version (.getFirst (.getRequestHeaders exchange) "Stripe-Version")}
                              _ (swap! requests conj request)
                              bytes (.getBytes (json/generate-string (respond request)) "UTF-8")]
                          (.set (.getResponseHeaders exchange) "Content-Type" "application/json")
                          (.sendResponseHeaders exchange 200 (alength bytes))
                          (with-open [output (.getResponseBody exchange)]
                            (.write output bytes))))))
    (.start server)
    (try
      (Stripe/overrideApiBase (str "http://127.0.0.1:" (.getPort (.getAddress server))))
      (set! Stripe/apiKey "sk_test_local_only")
      (set! Stripe/enableTelemetry false)
      (run requests)
      (finally
        (Stripe/overrideApiBase old-base)
        (set! Stripe/apiKey old-key)
        (set! Stripe/enableTelemetry old-telemetry)
        (.stop server 0)))))

(deftest checkout-creates-flexible-subscriptions-with-current-api
  (is (= "34.2.0" Stripe/VERSION))
  (is (= "2026-11-18.endive" Stripe/API_VERSION))
  (with-stripe-server
    (constantly {:id "cs_test_membership" :object "checkout.session" :url "https://checkout.stripe.test/session"})
    (fn [requests]
      (doseq [price-id ["price_membership" "plan_legacy" "plan_legacy-monthly" "plan_legacy_monthly" "plan_x?expand[]=customer"]]
        (let [session (auth/checkout-session "cus_member" price-id "https://example.test/success" "https://example.test/cancel")
              request (last @requests)]
          (is (= 303 (:status (auth/checkout-response session))))
          (is (= "https://checkout.stripe.test/session" (get-in (auth/checkout-response session) [:headers "Location"])))
          (is (= {:method "POST"
                  :path "/v1/checkout/sessions"
                  :query {}
                  :api-version "2026-11-18.endive"
                  :params {"customer" "cus_member"
                           "mode" "subscription"
                           "line_items[0][price]" price-id
                           "line_items[0][quantity]" "1"
                           "subscription_data[billing_mode][type]" "flexible"
                           "success_url" "https://example.test/success"
                           "cancel_url" "https://example.test/cancel"}}
                 request))))
      (auth/checkout-session "cus_member" "price_default")
      (is (= "https://www.clojuriststogether.org/signup-success/"
             (get-in (last @requests) [:params "success_url"])))
      (is (= (get-in (last @requests) [:params "success_url"])
             (get-in (last @requests) [:params "cancel_url"]))))))

(deftest backfill-uses-price-and-flexible-mode-without-changing-trial
  (with-stripe-server
    (fn [{:keys [path]}]
      (case path
        "/v1/customers" {:id "cus_backfill" :object "customer"}
        "/v1/subscriptions" {:id "sub_backfill" :object "subscription"}))
    (fn [requests]
      (let [writes (atom [])]
        (with-redefs [jdbc/execute! (fn [_ query] (swap! writes conj query))]
          (batch-fill/create-customer-and-subscription
           ::db {:id 42 :email "ada@example.test" :name "Ada" :subscription_plan "plan_legacy"}))
        (is (= ["/v1/customers" "/v1/subscriptions"] (mapv :path @requests)))
        (is (every? #(= "2026-11-18.endive" (:api-version %)) @requests))
        (is (= {"customer" "cus_backfill"
                "items[0][price]" "plan_legacy"
                "items[0][quantity]" "1"
                "billing_mode[type]" "flexible"
                "trial_period_days" "30"}
               (:params (second @requests))))
        (is (= [["UPDATE members SET stripe_customer_id = ? WHERE id = ?" "cus_backfill" 42]] @writes))))))

(defn recurring-price [id]
  {:id id :object "price" :active true :product "prod_membership"
   :billing_scheme "per_unit" :type "recurring" :unit_amount 4000 :currency "usd"
   :nickname "Monthly" :recurring {:interval "month" :interval_count 1 :usage_type "licensed"}})

(def product {:id "prod_membership" :object "product" :name "Developer membership"})

(defn html [hiccup]
  (str (h/html hiccup)))

(deftest legacy-and-modern-prices-render-in-all-membership-views
  (with-stripe-server
    (fn [{:keys [path]}]
      (cond
        (= path "/v1/products/prod_membership") product
        (= path "/v1/products") {:object "list" :data [product] :has_more false :url "/v1/products"}
        (= path "/v1/prices") {:object "list" :data [(recurring-price "plan_legacy") (assoc (recurring-price "price_modern") :metadata {"public" "true"})
                                                     (assoc (recurring-price "price_private") :metadata {"public" "false"})
                                                     (assoc (recurring-price "price_metered") :recurring {:usage_type "metered"})
                                                     (assoc (recurring-price "price_tiered") :billing_scheme "tiered" :unit_amount nil)]
                               :has_more false :url "/v1/prices"}
        :else (recurring-price (last (str/split path #"/")))))
    (fn [requests]
      (doseq [price-id ["plan_legacy" "price_modern"]]
        (let [price (auth/retrieve-price price-id)]
          (is (= {:id price-id :interval "month" :interval-count 1 :amount 4000 :currency "usd" :product "prod_membership"}
                 (auth/price->map price)))
          (is (str/includes? (html (auth/selected-plan-summary price (auth/retrieve-product "prod_membership"))) "every month"))
          (is (str/includes? (html (auth/current-plan price-id)) "Developer membership"))
          (is (str/includes? (html (auth/current-plan price-id)) "40"))))
      (let [rendered (html (auth/display-plans {::r/router (r/router [["/manage/subscribe" {:name :manage-subscribe}]])}))]
        (is (str/includes? rendered "plan_legacy"))
        (is (str/includes? rendered "price_modern"))
        (is (not (str/includes? rendered "price_private")))
        (is (not (str/includes? rendered "price_metered")))
        (is (not (str/includes? rendered "price_tiered"))))
      (is (= {"active" "true" "type" "recurring"}
             (:query (last @requests))))
      (is (every? #(str/starts-with? (:path %) "/v1/pr") @requests)))))

(deftest unsupported-price-shapes-are-rejected-before-rendering
  (doseq [price [(assoc (recurring-price "price_oneoff") :recurring nil)
                 (assoc (recurring-price "price_tiered") :unit_amount nil :billing_scheme "tiered")
                 (assoc-in (recurring-price "price_metered") [:recurring :usage_type] "metered")]]
    (with-stripe-server
      (constantly price)
      (fn [_]
        (is (= 422 (try
                     (auth/retrieve-price (:id price))
                     (catch clojure.lang.ExceptionInfo e
                       (get-in (ex-data e) [:response :status])))))))))

(deftest registration-and-subscribe-identifiers-accept-prices-and-legacy-plans
  (doseq [id ["price_123abc" "plan_123abc" "plan_legacy-monthly" "plan_legacy_monthly"
              "price_a/b" "plan_x?expand[]=customer"]]
    (is (s/valid? ::specs/plan-id id)))
  (doseq [id [nil "" "  " "price_" "plan_" "price_  " "plan_\t" "prod_abc" "custom-monthly"
              ["price_abc"] {:raw "price_abc"}]]
    (is (not (s/valid? ::specs/plan-id id)))))

(deftest registration-routes-preserve-custom-legacy-plan-ids
  (with-stripe-server
    (fn [{:keys [path]}]
      (if (= path "/v1/products/prod_membership")
        product
        (recurring-price (last (str/split path #"/")))))
    (fn [requests]
      (with-redefs [auth/retrieve-price-memo auth/retrieve-price
                    auth/retrieve-product-memo auth/retrieve-product]
        (let [handler (ig/init-key :app/handler {:db ::db :profile :test :store (memory/memory-store)})]
          (doseq [path ["/register/developer" "/register/company"]
                  id ["plan_legacy-monthly" "plan_legacy_monthly"]]
            (let [response (handler {:request-method :get
                                     :scheme :http
                                     :server-name "localhost"
                                     :server-port 3000
                                     :uri path
                                     :query-string (str "plan=" id)
                                     :headers {}})]
              (is (= 200 (:status response)))
              (is (str/includes? (if (string? (:body response)) (:body response) (slurp (:body response)))
                                 (str "value=\"" id "\"")))
              (is (= (str "/v1/prices/" id) (:path (first (take-last 2 @requests))))))))))))

(deftest membership-picker-groups-and-orders-by-monthly-cost
  (let [products [{:id "prod_company" :object "product" :name "Company tier" :metadata {"member_type" "company"}}
                  {:id "prod_high" :object "product" :name "Higher developer tier" :metadata {"member_type" "developer"}}
                  {:id "prod_low" :object "product" :name "Lower developer tier" :metadata {"member_type" "developer"}}
                  {:id "prod_unknown" :object "product" :name "Unclassified tier"}]
        prices [(assoc (recurring-price "price_company") :product "prod_company" :unit_amount 5000)
                (assoc (recurring-price "price_high") :product "prod_high" :unit_amount 2000)
                (-> (recurring-price "price_annual")
                    (assoc :product "prod_low" :unit_amount 12000)
                    (assoc-in [:recurring :interval] "year"))
                (assoc (recurring-price "price_monthly") :product "prod_low" :unit_amount 1000)
                (assoc (recurring-price "price_hidden") :product "prod_high" :unit_amount 1 :metadata {"public" "false"})
                (assoc (recurring-price "price_unknown") :product "prod_unknown")]]
    (with-stripe-server
      (fn [{:keys [path]}]
        {:object "list" :data (if (= path "/v1/products") products prices) :has_more false :url path})
      (fn [_]
        (let [rendered (html (auth/display-plans {::r/router (r/router [["/manage/subscribe" {:name :manage-subscribe}]])}))
              labels ["Developer memberships" "Lower developer tier" "price_monthly" "price_annual"
                      "Higher developer tier" "Company memberships" "Company tier" "Other memberships" "Unclassified tier"]
              positions (mapv #(.indexOf rendered %) labels)]
          (is (every? #(<= 0 %) positions))
          (is (apply < positions))
          (is (not (str/includes? rendered "price_hidden"))))))))
