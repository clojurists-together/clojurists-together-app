(ns org.clojuriststogether.app.webhooks
  (:require [ring.util.http-response :as response]
            [clojure.java.jdbc :as jdbc]
            [cheshire.core :as json]
            [honeysql.core :as sql])
  (:import (com.stripe.net Webhook)))

(defn handle-subscription-event [db payload]
  ;; Lookup customer from event
  ;; Update subscription_plan, or set it to null
  ;; TODO: handle race conditions where new subscription is created and then old one is deleted
  (let [object (get-in payload [:data :object])
        customer-id (get object :customer)
        _ (prn "customer" customer-id)
        _ (prn object)
        webhook-type (get payload :type)
        plan-id (if (= webhook-type "customer.subscription.deleted")
                  nil
                  (get-in object [:plan :id]))]
    (->> {:update :members
          :set {:subscription_plan plan-id}
          :where [:= :stripe_customer_id customer-id]}
         (sql/format)
         (jdbc/execute! db))))

(defn- verified-payload
  "Returns the parsed body, or nil unless signature is valid for it."
  [body signature webhook-secret]
  (try
    (Webhook/constructEvent body signature webhook-secret)
    (json/parse-string body true)
    (catch Exception _ nil)))

(defn stripe-webhook [db webhook-secret]
  ["/webhook/stripe"
   {:post
    {:handler (fn [req]
                (if-let [payload (verified-payload (slurp (:body req))
                                                   (get-in req [:headers "stripe-signature"])
                                                   webhook-secret)]
                  (let [webhook-type (get payload :type)
                        id (get payload :id)]
                    (prn "Handling" webhook-type)
                    (case webhook-type
                      ("customer.subscription.created" "customer.subscription.updated" "customer.subscription.deleted")
                      (handle-subscription-event db payload)
                      (prn "Ignoring webhook" {:webhook-type webhook-type}))
                    (response/ok {:type webhook-type
                                  :id id
                                  :status "accepted"}))
                  (response/bad-request {:error "Invalid Stripe signature"})))}}])
