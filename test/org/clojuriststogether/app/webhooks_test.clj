(ns org.clojuriststogether.app.webhooks-test
  (:require [clojure.java.io :as io]
            [clojure.java.jdbc :as jdbc]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [integrant.core :as ig]
            [org.clojuriststogether.app.routes])
  (:import (java.io ByteArrayInputStream)
           (javax.crypto Mac)
           (javax.crypto.spec SecretKeySpec)))

(def secret "whsec_test_secret")
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
  "POSTs through the app's real route tree. Returns the response status and every
  SQL statement sent to the database."
  [{:keys [body signature webhook-secret]}]
  (let [writes (atom [])
        handler (ig/init-key :app/handler {:db ::db
                                           :profile :test
                                           :stripe-webhook-secret webhook-secret})
        request (cond-> {:request-method :post
                         :scheme :http
                         :server-name "localhost"
                         :server-port 3000
                         :remote-addr "127.0.0.1"
                         :uri "/webhook/stripe"
                         :headers {}
                         :body (ByteArrayInputStream. (.getBytes body "UTF-8"))}
                  signature (assoc-in [:headers "stripe-signature"] signature))]
    (with-redefs [jdbc/execute! (fn [_db sql-and-params]
                                  (swap! writes conj sql-and-params))]
      {:status (:status (handler request))
       :writes @writes})))

(deftest correctly-signed-events-update-the-subscription-plan
  (testing "a created event sets the plan"
    (is (= {:status 200
            :writes [["UPDATE members SET subscription_plan = ? WHERE stripe_customer_id = ?"
                      plan-id customer-id]]}
           (post-webhook {:body created-event
                          :signature (signature-header created-event secret)
                          :webhook-secret secret}))))
  (testing "a deleted event clears the plan"
    (is (= {:status 200
            :writes [["UPDATE members SET subscription_plan = NULL WHERE stripe_customer_id = ?"
                      customer-id]]}
           (post-webhook {:body deleted-event
                          :signature (signature-header deleted-event secret)
                          :webhook-secret secret})))))

(def rejected-requests
  [["no signature header" forged-event nil]
   ["empty signature header" forged-event ""]
   ["garbage signature header" forged-event "nonsense"]
   ["non-numeric timestamp" forged-event "t=abc,v1=deadbeef"]
   ["timestamp key with no value" forged-event "t"]
   ["signature key with no value" forged-event "t=1,v1"]
   ["signed with the wrong secret" forged-event (signature-header forged-event "whsec_wrong")]
   ["signature for a different body" forged-event (signature-header created-event secret)]
   ["timestamp outside the replay window" forged-event
    (signature-header forged-event secret (- (now-seconds) 3600))]
   ["unsigned malformed JSON" "not json" nil]
   ["wrongly signed malformed JSON" "not json" (signature-header "not json" "whsec_wrong")]
   ["wrongly signed JSON with trailing garbage" "{} trailing"
    (signature-header "{} trailing" "whsec_wrong")]])

(deftest requests-without-a-valid-signature-get-400-and-change-nothing
  (doseq [[description body signature] rejected-requests]
    (testing description
      (is (= {:status 400 :writes []}
             (post-webhook {:body body :signature signature :webhook-secret secret}))))))

(deftest an-unconfigured-secret-rejects-every-request-and-changes-nothing
  (doseq [webhook-secret [nil ""]]
    (testing (pr-str webhook-secret)
      (is (= {:status 400 :writes []}
             (post-webhook {:body forged-event
                            :signature (signature-header forged-event "whsec_guess")
                            :webhook-secret webhook-secret}))))))
