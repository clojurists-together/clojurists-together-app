(ns stripe-verify
  "Disposable, loopback-only verification against Stripe test mode and local PostgreSQL."
  (:require [cheshire.core :as json]
            [clojure.java.io :as io]
            [clojure.java.jdbc :as jdbc]
            [clojure.string :as str]
            [integrant.core :as ig]
            [org.clojuriststogether.app.admin.batch-fill :as batch-fill]
            [org.clojuriststogether.app.db]
            [org.clojuriststogether.app.email :as email]
            [org.clojuriststogether.app.pages.auth :as auth]
            [org.clojuriststogether.app.routes]
            [ring.adapter.jetty :as jetty]
            [ring.middleware.session.memory :as memory])
  (:import (com.stripe Stripe)
           (com.stripe.model Account Customer Invoice Plan Price Product StripeObject Subscription)
           (com.stripe.model.checkout Session)
           (com.stripe.model.testhelpers TestClock)
           (java.io ByteArrayInputStream)
           (java.time Instant)
           (java.util UUID)))

(defonce evidence (atom {:requests [] :webhooks [] :actions []}))
(defonce objects (atom {}))
(defonce operation-lock (Object.))
(def db {:connection-uri "jdbc:postgresql://127.0.0.1:55437/stripe_upgrade_verify"})
(def run-dir (atom nil))
(def run-id (str "verify-" (subs (str (UUID/randomUUID)) 0 12)))
(def control-token (str (UUID/randomUUID)))

(defn now [] (str (Instant/now)))
(defn object-data [^StripeObject object] (json/parse-string (.toJson object) true))
(defn private-write! [name data]
  (let [file (io/file @run-dir name)]
    (.createNewFile file)
    (.setReadable file false false)
    (.setWritable file false false)
    (.setReadable file true true)
    (.setWritable file true true)
    (spit file (if (string? data) data (json/generate-string data {:pretty true})))))
(defn persist! [] (locking evidence (private-write! "evidence.json" @evidence)))

(defn record! [operation ^StripeObject object]
  (let [data (object-data object)
        ^com.stripe.net.StripeResponse response (.getLastResponse object)]
    (when (contains? data :livemode)
      (assert (false? (:livemode data)) "Refusing a live Stripe object"))
    (swap! evidence update :requests conj
           {:at (now) :operation operation :object-id (:id data)
            :livemode (:livemode data) :request-id (some-> response .requestId)
            :sdk-response "success"})
    (persist!)
    object))

(defn tagged! [kind object]
  (let [data (object-data object)]
    (assert (false? (:livemode data)) "Object must be in test mode")
    (assert (= run-id (get-in data [:metadata :verification_run])) "Object belongs to another run")
    (swap! objects assoc kind (:id data))
    object))

(defn members []
  (jdbc/query db ["SELECT id, stripe_customer_id, subscription_plan FROM members ORDER BY id"]))

(defn customer! [kind extra]
  (tagged! kind
           (record! (str "customer.create/" (name kind))
                    (Customer/create (merge {"email" (str run-id "+" (name kind) "@example.test")
                                             "name" "Disposable Stripe verification"
                                             "metadata" {"verification_run" run-id}}
                                            extra)))))

(defn member! [customer-id suffix]
  (jdbc/insert! db :members
                {:email (str run-id "+" suffix "@example.test")
                 :person_name "Disposable Stripe verification"
                 :member_type "developer" :stripe_customer_id customer-id}))

(defn login-link! [customer-id name]
  (let [id (:id (first (jdbc/query db ["SELECT id FROM members WHERE stripe_customer_id = ?" customer-id])))
        token (UUID/randomUUID)]
    (jdbc/insert! db :login_links {:id token :member_id id})
    (private-write! name (str "http://127.0.0.1:3347/magic?token=" token))))

(defn safe-subscription [data]
  {:id (:id data) :customer (:customer data) :livemode (:livemode data)
   :status (:status data) :billing-mode (get-in data [:billing_mode :type])
   :price-ids (mapv #(get-in % [:price :id]) (get-in data [:items :data]))
   :item-ids (mapv :id (get-in data [:items :data]))
   :current-period-end (get-in data [:items :data 0 :current_period_end])
   :latest-invoice (:latest_invoice data) :trial-start (:trial_start data) :trial-end (:trial_end data)})

(defn subscription! [id]
  (record! "subscription.retrieve" (Subscription/retrieve ^String id)))

(defn inspect! []
  (when-let [id (:checkout @objects)]
    (let [data (object-data (record! "checkout.retrieve" (Session/retrieve ^String id)))]
      (swap! evidence assoc :checkout (select-keys data [:id :status :payment_status :customer :subscription :livemode]))
      (when-let [id (:subscription data)]
        (swap! objects assoc :flexible-subscription id))))
  (doseq [kind [:flexible-subscription :classic-subscription :clock-subscription :backfill-subscription]
          :let [id (kind @objects)] :when id]
    (let [data (object-data (subscription! id))]
      (swap! evidence assoc kind (safe-subscription data))
      (when-let [invoice-id (:latest_invoice data)]
        (let [invoice (object-data (record! "invoice.retrieve" (Invoice/retrieve ^String invoice-id)))]
          (swap! evidence assoc-in [:invoices invoice-id]
                 (select-keys invoice [:id :status :paid :amount_paid :amount_due :billing_reason :livemode :customer]))))))
  (when-let [id (:clock @objects)]
    (swap! evidence assoc :clock
           (select-keys (object-data (record! "test_clock.retrieve" (TestClock/retrieve ^String id)))
                        [:id :status :frozen_time :livemode])))
  (swap! evidence assoc :members (members) :objects @objects :updated-at (now))
  (persist!)
  @evidence)

(defn update-price! []
  (let [subscription (subscription! (:flexible-subscription @objects))
        data (object-data subscription)
        _ (assert (= (:checkout-customer @objects) (:customer data)))
        _ (assert (= "flexible" (get-in data [:billing_mode :type])))
        item-id (get-in data [:items :data 0 :id])]
    (record! "subscription.update-price"
             (.update ^Subscription subscription
                      ^java.util.Map {"items" [{"id" item-id "price" (:updated-price @objects)}]
                                      "proration_behavior" "none"}))))

(defn complete! []
  (inspect!)
  (let [subscription (subscription! (:flexible-subscription @objects))
        data (object-data subscription)]
    (assert (= "active" (:status data)))
    (assert (= "flexible" (get-in data [:billing_mode :type])))
    (assert (= (:checkout-customer @objects) (:customer data)))
    (assert (= (:price @objects) (get-in data [:items :data 0 :price :id])))
    (tagged! :flexible-subscription
             (record! "checkout.subscription.tag"
                      (.update ^Subscription subscription ^java.util.Map {"metadata" {"verification_run" run-id}})))))

(defn cancel! []
  (let [subscription (subscription! (:flexible-subscription @objects))]
    (assert (= (:checkout-customer @objects) (:customer (object-data subscription))))
    (record! "subscription.cancel" (.cancel ^Subscription subscription))))

(defn classic! []
  (assert (nil? (:classic-subscription @objects)) "Classic fixture already created")
  (let [plan (record! "plan.create-legacy"
                      (Plan/create {"id" (str "plan_" run-id "_legacy-monthly")
                                    "product" (:product @objects) "amount" 500 "currency" "usd" "interval" "month"
                                    "metadata" {"verification_run" run-id}}))
        _ (tagged! :legacy-plan plan)
        customer (customer! :classic-customer {"payment_method" "pm_card_visa"
                                               "invoice_settings" {"default_payment_method" "pm_card_visa"}})
        customer-id (.getId ^Customer customer)]
    (member! customer-id "classic")
    (login-link! customer-id "classic-login-url.txt")
    (tagged! :classic-subscription
             (record! "subscription.create-classic"
                      (Subscription/create {"customer" customer-id "items" [{"price" (.getId ^Plan plan)}]
                                            "billing_mode" {"type" "classic"}
                                            "metadata" {"verification_run" run-id}})))
    (let [price (record! "auth.retrieve-price-legacy" (auth/retrieve-price (.getId ^Plan plan)))
          archive (record! "price.archive-legacy" (.update ^Price price ^java.util.Map {"active" false}))
          price (record! "auth.retrieve-archived-legacy-price" (auth/retrieve-price (.getId ^Price archive)))]
      (assert (= (.getId ^Plan plan) (.getId ^Price price)))
      (swap! evidence assoc :archived-legacy-price (select-keys (object-data price) [:id :active :livemode])))
    (let [portal (record! "auth.self-serve-session-classic" (auth/self-serve-session customer-id))]
      (private-write! "classic-portal-url.txt" (.getUrl portal))
      (swap! evidence assoc :classic-portal {:id (.getId portal) :created true}))
    (swap! evidence assoc :legacy-render {:current-plan (boolean (seq (auth/current-plan (.getId ^Plan plan))))})))

(defn clock-create! []
  (assert (nil? (:clock @objects)) "Clock fixture already created")
  (let [clock (record! "test_clock.create" (TestClock/create {"frozen_time" (.getEpochSecond (Instant/now))
                                                              "name" run-id}))
        _ (swap! objects assoc :clock (.getId ^TestClock clock))
        customer (customer! :clock-customer {"test_clock" (.getId ^TestClock clock)
                                             "payment_method" "pm_card_visa"
                                             "invoice_settings" {"default_payment_method" "pm_card_visa"}})
        id (.getId ^Customer customer)]
    (member! id "clock")
    (tagged! :clock-subscription
             (record! "subscription.create-clock-flexible"
                      (Subscription/create {"customer" id "items" [{"price" (:price @objects)}]
                                            "billing_mode" {"type" "flexible"}
                                            "metadata" {"verification_run" run-id}})))))

(defn clock-advance! []
  (let [clock (record! "test_clock.retrieve-before-advance" (TestClock/retrieve ^String (:clock @objects)))
        subscription (subscription! (:clock-subscription @objects))
        end (get-in (object-data subscription) [:items :data 0 :current_period_end])]
    (assert (= "ready" (:status (object-data clock))))
    (record! "test_clock.advance-one-cycle"
             (.advance ^TestClock clock ^java.util.Map {"frozen_time" (+ end 7200)}))))

(defn backfill! []
  (assert (nil? (:backfill-customer @objects)) "Backfill fixture already created")
  (let [email (str run-id "+backfill@example.test")
        member (first (jdbc/insert! db :members {:email email :person_name "Disposable backfill"
                                                 :member_type "developer" :stripe_customer_id "missing"}))]
    (batch-fill/create-customer-and-subscription db {:id (:id member) :email email :name "Disposable backfill"
                                                     :subscription_plan (:price @objects)})
    (let [customer-id (:stripe_customer_id (first (jdbc/query db ["SELECT stripe_customer_id FROM members WHERE id = ?" (:id member)])))
          customer (record! "backfill.customer.retrieve" (Customer/retrieve ^String customer-id))]
      (record! "backfill.customer.tag" (.update ^Customer customer ^java.util.Map {"metadata" {"verification_run" run-id}}))
      (swap! objects assoc :backfill-customer customer-id)
      (let [subscriptions (record! "backfill.subscription.list" (Subscription/list ^java.util.Map {"customer" customer-id}))
            subscription (first (.getData subscriptions))]
        (assert subscription)
        (tagged! :backfill-subscription
                 (record! "backfill.subscription.tag" (.update ^Subscription subscription ^java.util.Map {"metadata" {"verification_run" run-id}})))))))

(defn portal! []
  (let [customer (record! "customer.retrieve-before-portal" (Customer/retrieve ^String (:checkout-customer @objects)))
        _ (tagged! :checkout-customer customer)
        portal (record! "auth.self-serve-session-flexible" (auth/self-serve-session (.getId ^Customer customer)))]
    (private-write! "flexible-portal-url.txt" (.getUrl portal))
    (swap! evidence assoc :flexible-portal {:id (.getId portal) :created true})))

(defn json-response [status body]
  {:status status :headers {"Content-Type" "application/json"} :body (json/generate-string body)})

(defn control! [req]
  (if-not (= control-token (get-in req [:headers "x-verification-token"]))
    (json-response 403 {:error "Forbidden"})
    (locking operation-lock
      (try
        (let [action (:action (json/parse-string (slurp (:body req)) true))]
          (case action
            "inspect" nil
            "complete" (complete!)
            "update-price" (update-price!)
            "cancel" (cancel!)
            "classic" (classic!)
            "clock-create" (clock-create!)
            "clock-advance" (clock-advance!)
            "backfill" (backfill!)
            "portal" (portal!)
            (throw (ex-info "Unknown action" {})))
          (swap! evidence update :actions conj {:at (now) :action action :result "ok"})
          (json-response 200 (inspect!)))
        (catch Exception e
          (let [failure (merge {:at (now) :result "error" :class (.getName (class e))}
                               (when (instance? com.stripe.exception.StripeException e)
                                 {:stripe-code (some-> ^com.stripe.exception.StripeException e .getStripeError .getCode)
                                  :request-id (.getRequestId ^com.stripe.exception.StripeException e)}))]
            (swap! evidence update :actions conj failure)
            (persist!)
            (json-response 500 failure)))))))

(defn handler [app]
  (fn [req]
    (cond
      (and (= :get (:request-method req)) (= "/__verify/status" (:uri req)))
      (json-response 200 @evidence)

      (and (= :post (:request-method req)) (= "/__verify/action" (:uri req)))
      (control! req)

      (= "/__verify/success" (:uri req))
      {:status 200 :headers {"Content-Type" "text/html"}
       :body "<h1>Stripe test Checkout returned successfully</h1><p>Verification is checking the signed webhook and database separately.</p>"}

      (= "/__verify/cancel" (:uri req))
      {:status 200 :body "Checkout cancelled"}

      (= "/webhook/stripe" (:uri req))
      (let [body (slurp (:body req))
            event (try (json/parse-string body true) (catch Exception _ {}))
            response (app (assoc req :body (ByteArrayInputStream. (.getBytes body "UTF-8"))))
            object (get-in event [:data :object])
            customer (:customer object)
            ours? (or (= run-id (get-in object [:metadata :verification_run]))
                      (some #{customer} (vals @objects)))]
        (when (or ours? (not= 200 (:status response)))
          (swap! evidence update :webhooks conj
                 {:at (now) :id (:id event) :type (:type event) :api-version (:api_version event)
                  :livemode (:livemode event) :customer customer :object-id (:id object)
                  :http-status (:status response) :signature-present (boolean (get-in req [:headers "stripe-signature"]))
                  :members-after (members)})
          (swap! evidence assoc :members (members))
          (persist!))
        response)

      :else (app req))))

(defn setup! []
  (swap! evidence assoc :run-id run-id :started-at (now) :sdk-version Stripe/VERSION :api-version Stripe/API_VERSION
         :boundary "Real api.stripe.com test API; genuine Stripe CLI signed HTTP delivery; production Ring routes; isolated PostgreSQL")
  (let [account (record! "account.retrieve" (Account/retrieve))]
    (assert (= "acct_1FcFTzGYL0HLUlSL" (.getId ^Account account)) "Unexpected Stripe account")
    (swap! evidence assoc :account-id (.getId ^Account account)))
  (let [product (tagged! :product (record! "product.create" (Product/create {"name" (str "Disposable " run-id)
                                                                             "metadata" {"verification_run" run-id}})))]
    (doseq [[kind amount] [[:price 500] [:updated-price 700]]]
      (tagged! kind
               (record! (str "price.create/" (name kind))
                        (Price/create {"product" (.getId ^Product product) "currency" "usd" "unit_amount" amount
                                       "recurring" {"interval" "month"} "metadata" {"verification_run" run-id}})))))
  (let [customer (customer! :checkout-customer {})
        customer-id (.getId ^Customer customer)
        _ (member! customer-id "checkout")
        _ (login-link! customer-id "flexible-login-url.txt")
        session (record! "auth.checkout-session"
                         (auth/checkout-session customer-id (:price @objects)
                                                "http://127.0.0.1:3347/__verify/success"
                                                "http://127.0.0.1:3347/__verify/cancel"))]
    (swap! objects assoc :checkout (.getId ^Session session))
    (private-write! "checkout-url.txt" (.getUrl ^Session session))
    (record! "checkout.tag" (.update ^Session session ^java.util.Map {"metadata" {"verification_run" run-id}})))
  (inspect!)
  (private-write! "control.json" {:token control-token :url "http://127.0.0.1:3347/__verify/action"}))

(defn -main [& [directory]]
  (assert directory "Private runtime directory is required")
  (assert (str/starts-with? (or (System/getenv "STRIPE_SECRET_KEY") "") "sk_test_") "Only sk_test_ credentials are allowed")
  (assert (str/starts-with? (or (System/getenv "STRIPE_WEBHOOK_SECRET") "") "whsec_") "A real Stripe CLI signing secret is required")
  (assert (= (:connection-uri db) (System/getenv "JDBC_DATABASE_URL")) "Only the isolated local database is allowed")
  (assert (= "https://api.stripe.com" (Stripe/getApiBase)) "Only the real Stripe API is allowed")
  (reset! run-dir directory)
  (let [previous (io/file directory "evidence.json")]
    (when (.exists previous)
      (let [saved (json/parse-string (slurp previous) true)]
        (assert (get-in saved [:objects :checkout]) "Incomplete prior setup. Use a fresh runtime directory.")
        (assert (= "acct_1FcFTzGYL0HLUlSL" (:account-id saved)))
        (alter-var-root #'run-id (constantly (:run-id saved)))
        (reset! evidence saved)
        (reset! objects (:objects saved)))))
  (set! Stripe/apiKey (System/getenv "STRIPE_SECRET_KEY"))
  (set! Stripe/enableTelemetry false)
  (let [app (ig/init-key :app/handler {:db db :profile :test :store (memory/memory-store)
                                       :email-service (email/->LoggingEmailService {})
                                       :stripe-webhook-secret (System/getenv "STRIPE_WEBHOOK_SECRET")})
        server (jetty/run-jetty (handler app) {:host "127.0.0.1" :port 3347 :join? false})]
    (.addShutdownHook (Runtime/getRuntime) (Thread. #(.stop server)))
    (if (:checkout @objects)
      (do
        (let [account (record! "account.retrieve-on-resume" (Account/retrieve))]
          (assert (= (:account-id @evidence) (.getId ^Account account))))
        (login-link! (:checkout-customer @objects) "flexible-login-url.txt")
        (when-let [id (:classic-customer @objects)] (login-link! id "classic-login-url.txt"))
        (private-write! "control.json" {:token control-token :url "http://127.0.0.1:3347/__verify/action"})
        (inspect!))
      (setup!))
    (println "Stripe test verification ready; private URLs and sanitized evidence are in the runtime directory.")
    (.join server)))
