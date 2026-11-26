(ns org.clojuriststogether.app.webhooks-test
  (:require [cheshire.core :as json]
            [clojure.java.io :as io]
            [clojure.java.jdbc :as jdbc]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [integrant.core :as ig]
            [org.clojuriststogether.app.routes]
            [org.clojuriststogether.app.slack :as slack])
  (:import (java.io ByteArrayInputStream)
           (javax.crypto Mac)
           (javax.crypto.spec SecretKeySpec)))

(def secret "whsec_test_secret")
(def secondary-secret "whsec_secondary_test_secret")
(def customer-id "cus_HGNGsBYlu1rIJB")
(def plan-id "plan_GY8ntpqaHEUlza")
(def created-event (slurp (io/resource "webhooks/customer.subscription.created.json")))
(def deleted-event (str/replace created-event
                                "customer.subscription.created"
                                "customer.subscription.deleted"))
(def forged-event (str/replace created-event plan-id "forged-plan"))

(defn now-seconds []
  (quot (System/currentTimeMillis) 1000))

(defn hmac-sha256-hex [key message]
  (let [mac (doto (Mac/getInstance "HmacSHA256")
              (.init (SecretKeySpec. (.getBytes key "UTF-8") "HmacSHA256")))]
    (->> (.doFinal mac (.getBytes message "UTF-8"))
         (map #(format "%02x" %))
         (apply str))))

(defn signature-header
  ([body key] (signature-header body key (now-seconds)))
  ([body key timestamp]
   (format "t=%d,v1=%s" timestamp (hmac-sha256-hex key (str timestamp "." body)))))

(defn post-webhook
  "POSTs through the app's real route tree. Returns the response status and error,
  and every SQL statement sent to the database."
  [{:keys [body signature webhook-secret webhook-secondary-secret]}]
  (let [writes (atom [])
        handler (ig/init-key :app/handler {:db ::db
                                           :profile :test
                                           :stripe-webhook-secret webhook-secret
                                           :stripe-webhook-secondary-secret webhook-secondary-secret})
        request (cond-> {:request-method :post
                         :scheme :http
                         :server-name "localhost"
                         :server-port 3000
                         :remote-addr "127.0.0.1"
                         :uri "/webhook/stripe"
                         :headers {}
                         :body (ByteArrayInputStream. (.getBytes body "UTF-8"))}
                  signature (assoc-in [:headers "stripe-signature"] signature))]
    (with-redefs [jdbc/db-transaction* (fn [db f & _] (f db))
                  slack/enqueue! (fn [_ _])
                  jdbc/execute! (fn [_db sql-and-params]
                                  (swap! writes conj sql-and-params))]
      (let [response (handler request)]
        {:status (:status response)
         :error (:error (json/parse-string (slurp (:body response)) true))
         :writes @writes}))))

(deftest correctly-signed-events-update-the-subscription-plan
  (testing "a created event sets the plan"
    (is (= {:status 200
            :error nil
            :writes [["UPDATE members SET subscription_plan = ? WHERE stripe_customer_id = ?"
                      plan-id customer-id]]}
           (post-webhook {:body created-event
                          :signature (signature-header created-event secret)
                          :webhook-secret secret}))))
  (testing "a deleted event clears the plan"
    (is (= {:status 200
            :error nil
            :writes [["UPDATE members SET subscription_plan = NULL WHERE stripe_customer_id = ?"
                      customer-id]]}
           (post-webhook {:body deleted-event
                          :signature (signature-header deleted-event secret)
                          :webhook-secret secret})))))

(def unsigned-requests
  [["no signature header" forged-event nil]
   ["empty signature header" forged-event ""]
   ["blank signature header" forged-event "  "]
   ["unsigned malformed JSON" "not json" nil]])

(def wrongly-signed-requests
  [["garbage signature header" forged-event "nonsense"]
   ["non-numeric timestamp" forged-event "t=abc,v1=deadbeef"]
   ["timestamp key with no value" forged-event "t"]
   ["signature key with no value" forged-event "t=1,v1"]
   ["signed with the wrong secret" forged-event (signature-header forged-event "whsec_wrong")]
   ["signature for a different body" forged-event (signature-header created-event secret)]
   ["timestamp outside the replay window" forged-event
    (signature-header forged-event secret (- (now-seconds) 3600))]
   ["wrongly signed malformed JSON" "not json" (signature-header "not json" "whsec_wrong")]
   ["wrongly signed JSON with trailing garbage" "{} trailing"
    (signature-header "{} trailing" "whsec_wrong")]])

(deftest unsigned-requests-get-a-missing-signature-400-and-change-nothing
  (doseq [[description body signature] unsigned-requests]
    (testing description
      (is (= {:status 400 :error "Missing Stripe signature" :writes []}
             (post-webhook {:body body :signature signature :webhook-secret secret}))))))

(deftest wrongly-signed-requests-get-an-invalid-signature-400-and-change-nothing
  (doseq [[description body signature] wrongly-signed-requests]
    (testing description
      (is (= {:status 400 :error "Invalid Stripe signature" :writes []}
             (post-webhook {:body body :signature signature :webhook-secret secret}))))))

(deftest an-unconfigured-secret-rejects-every-request-and-changes-nothing
  (doseq [webhook-secret [nil "" " \t"]
          webhook-secondary-secret [nil "" " \t"]
          signing-secret ["whsec_guess" " " "\t"]]
    (testing (pr-str [webhook-secret webhook-secondary-secret signing-secret])
      (is (= {:status 400 :error "Invalid Stripe signature" :writes []}
             (post-webhook {:body forged-event
                            :signature (signature-header forged-event signing-secret)
                            :webhook-secret webhook-secret
                            :webhook-secondary-secret webhook-secondary-secret}))))))

(defn subscription-event [object]
  (-> (json/parse-string created-event true)
      (assoc :api_version "2026-11-18.endive")
      (assoc-in [:data :object] (merge {:id "sub_legacy" :object "subscription"} object))))

(defn signed-event-result [event]
  (let [body (json/generate-string event)]
    (post-webhook {:body body
                   :signature (signature-header body secret)
                   :webhook-secret secret})))

(defn queued-result [event status error & membership-writes]
  {:status status
   :error error
   :writes (vec membership-writes)})

(def modern-subscription
  {:id "sub_modern" :object "subscription" :customer customer-id
   :status "active"
   :items {:object "list" :data [{:id "si_member" :object "subscription_item"
                                  :price {:id "price_member" :object "price"}}]
           :has_more false}})

(deftest signed-modern-subscriptions-store-the-price-with-either-billing-mode
  (doseq [mode ["classic" "flexible"]
          type ["customer.subscription.created" "customer.subscription.updated"]]
    (testing (str mode " " type " updates membership")
      (let [event (assoc (subscription-event (assoc modern-subscription :billing_mode {:type mode})) :type type)]
        (is (= (queued-result event 200 nil
                              ["UPDATE members SET subscription_plan = ? WHERE stripe_customer_id = ?"
                               "price_member" customer-id])
               (signed-event-result event))))))
  (testing "modern items preserve a legacy Price id and existing membership mapping"
    (let [event (subscription-event (assoc-in modern-subscription [:items :data 0 :price :id] plan-id))]
      (is (= (queued-result event 200 nil
                            ["UPDATE members SET subscription_plan = ? WHERE stripe_customer_id = ?"
                             plan-id customer-id])
             (signed-event-result event))))))

(deftest signed-events-preserve-custom-legacy-identifiers
  (doseq [id ["plan_legacy-monthly" "plan_legacy_monthly" "custom-monthly" "custom'; --"]
          object [{:customer customer-id :plan {:id id}}
                  (assoc-in modern-subscription [:items :data 0 :price :id] id)]
          type ["customer.subscription.created" "customer.subscription.updated"]]
    (testing (str id " " type " " (if (:items object) "modern item" "legacy Plan"))
      (let [event (assoc (subscription-event object) :type type)]
        (is (= (queued-result event 200 nil
                              ["UPDATE members SET subscription_plan = ? WHERE stripe_customer_id = ?"
                               id customer-id])
               (signed-event-result event)))))))

(deftest signed-invalid-subscriptions-fail-without-changing-membership
  (doseq [[description object]
          [["nil legacy Plan id" {:customer customer-id :plan {:id nil}}]
           ["empty legacy Plan id" {:customer customer-id :plan {:id ""}}]
           ["blank legacy Plan id" {:customer customer-id :plan {:id " \t"}}]
           ["legacy Plan id is a collection" {:customer customer-id :plan {:id ["custom-monthly"]}}]
           ["missing Price and legacy Plan" (dissoc modern-subscription :items)]
           ["no items" (assoc-in modern-subscription [:items :data] [])]
           ["several items" (update-in modern-subscription [:items :data] conj {:price {:id "price_second"}})]
           ["truncated items" (assoc-in modern-subscription [:items :has_more] true)]
           ["item is not an object" (assoc-in modern-subscription [:items :data] ["price_member"])]
           ["missing modern Price id" (assoc-in modern-subscription [:items :data 0 :price] {})]
           ["Price is a scalar" (assoc-in modern-subscription [:items :data 0 :price] "price_member")]
           ["Price id is SQL" (assoc-in modern-subscription [:items :data 0 :price :id] {:raw "'price_member'"})]
           ["Price id is a collection" (assoc-in modern-subscription [:items :data 0 :price :id] ["price_member"])]
           ["empty Price id" (assoc-in modern-subscription [:items :data 0 :price :id] "")]
           ["blank Price id" (assoc-in modern-subscription [:items :data 0 :price :id] " \t")]
           ["conflicting legacy id" (assoc modern-subscription :plan {:id plan-id})]
           ["nil modern id cannot fall back to legacy" (-> modern-subscription
                                                           (assoc :plan {:id plan-id})
                                                           (assoc-in [:items :data 0 :price :id] nil))]
           ["blank modern id cannot fall back to legacy" (-> modern-subscription
                                                             (assoc :plan {:id plan-id})
                                                             (assoc-in [:items :data 0 :price :id] " "))]
           ["malformed modern Price cannot fall back to legacy" (-> modern-subscription
                                                                    (assoc :plan {:id plan-id})
                                                                    (assoc-in [:items :data 0 :price] "custom-monthly"))]
           ["missing customer" (dissoc modern-subscription :customer)]
           ["customer is SQL" (assoc modern-subscription :customer {:raw "'cus_member'"})]
           ["malformed customer" (assoc modern-subscription :customer "cus_")]]]
    (testing description
      (let [event (subscription-event object)]
        (is (= (queued-result event 400 "Invalid Stripe subscription identifiers")
               (signed-event-result event)))))))

(deftest signed-deletion-clears-without-requiring-subscription-items
  (let [event (assoc (subscription-event (dissoc modern-subscription :items))
                     :type "customer.subscription.deleted")]
    (is (= (queued-result event 200 nil
                          ["UPDATE members SET subscription_plan = NULL WHERE stripe_customer_id = ?" customer-id])
           (signed-event-result event)))))

(deftest overlapping-signing-secrets-preserve-legacy-and-modern-membership-events
  (doseq [signing-secret [secret secondary-secret]
          template [(json/parse-string created-event true)
                    (subscription-event modern-subscription)]
          type ["customer.subscription.created" "customer.subscription.updated" "customer.subscription.deleted"]]
    (testing (str type " " (:api_version template) " "
                  (if (= secret signing-secret) "primary" "secondary"))
      (let [event (assoc template :type type)
            body (json/generate-string event)
            deleted? (= type "customer.subscription.deleted")
            membership-write (if deleted?
                               ["UPDATE members SET subscription_plan = NULL WHERE stripe_customer_id = ?"
                                customer-id]
                               ["UPDATE members SET subscription_plan = ? WHERE stripe_customer_id = ?"
                                (or (get-in template [:data :object :items :data 0 :price :id]) plan-id)
                                customer-id])]
        (is (= (queued-result event 200 nil membership-write)
               (post-webhook {:body body
                              :signature (signature-header body signing-secret)
                              :webhook-secret secret
                              :webhook-secondary-secret secondary-secret})))))))

(deftest overlapping-signing-secrets-reject-unsigned-and-invalid-requests
  (doseq [[requests error] [[unsigned-requests "Missing Stripe signature"]
                            [(conj wrongly-signed-requests
                                   ["expired secondary signature" forged-event
                                    (signature-header forged-event secondary-secret (- (now-seconds) 3600))]
                                   ["forged body with secondary signature" forged-event
                                    (signature-header created-event secondary-secret)])
                             "Invalid Stripe signature"]]
          [description body signature] requests]
    (testing description
      (is (= {:status 400 :error error :writes []}
             (post-webhook {:body body :signature signature
                            :webhook-secret secret
                            :webhook-secondary-secret secondary-secret}))))))

(deftest blank-secondary-secret-preserves-primary-verification
  (doseq [blank-secret [nil "" " \t"]]
    (testing (pr-str blank-secret)
      (is (= (post-webhook {:body created-event
                            :signature (signature-header created-event secret)
                            :webhook-secret secret})
             (post-webhook {:body created-event
                            :signature (signature-header created-event secret)
                            :webhook-secret secret
                            :webhook-secondary-secret blank-secret})))
      (is (= {:status 400 :error "Invalid Stripe signature" :writes []}
             (post-webhook {:body forged-event
                            :signature (signature-header forged-event " ")
                            :webhook-secret secret
                            :webhook-secondary-secret blank-secret}))))))

(deftest secondary-signature-is-accepted-without-a-primary-secret
  (is (= (post-webhook {:body created-event
                        :signature (signature-header created-event secret)
                        :webhook-secret secret})
         (post-webhook {:body created-event
                        :signature (signature-header created-event secondary-secret)
                        :webhook-secondary-secret secondary-secret}))))
