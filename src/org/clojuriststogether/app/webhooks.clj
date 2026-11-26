(ns org.clojuriststogether.app.webhooks
  (:require [ring.util.http-response :as response]
            [clojure.java.jdbc :as jdbc]
            [cheshire.core :as json]
            [clojure.string :as str]
            [honey.sql :as sql]
            [org.clojuriststogether.app.slack :as slack]
            [org.clojuriststogether.app.spec :as specs])
  (:import (com.stripe.net Webhook)))

(defn- invalid-subscription! []
  (throw (ex-info "Invalid Stripe subscription identifiers"
                  {:type ::invalid-subscription})))

(defn- subscription-price-id [object]
  (let [items (:items object)
        data (:data items)
        legacy-id (get-in object [:plan :id])]
    (when (and (contains? object :items)
               (not (and (map? items)
                         (vector? data)
                         (= 1 (count data))
                         (map? (first data))
                         (not (:has_more items)))))
      (invalid-subscription!))
    (let [item (first data)
          modern? (contains? item :price)
          price-id (if modern? (get-in item [:price :id]) legacy-id)]
      (when-not (and (specs/price-id? price-id)
                     (or (nil? legacy-id)
                         (and (specs/price-id? legacy-id)
                              (= legacy-id price-id))))
        (invalid-subscription!))
      price-id)))

(defn handle-subscription-event [db payload]
  ;; TODO: handle race conditions where new subscription is created and then old one is deleted
  (let [object (get-in payload [:data :object])
        customer-id (:customer object)
        _ (when-not (and (string? customer-id)
                         (re-matches #"cus_[A-Za-z0-9]+" customer-id))
            (invalid-subscription!))
        price-id (when-not (= (:type payload) "customer.subscription.deleted")
                   (subscription-price-id object))]
    (->> {:update :members
          :set {:subscription_plan price-id}
          :where [:= :stripe_customer_id customer-id]}
         (sql/format)
         (jdbc/execute! db))))

(defn- verified-payload
  "Returns the parsed body, or nil unless signature is valid for it."
  [body signature accepted-signing-secrets]
  (try
    (when (some (fn [secret]
                  (try
                    (Webhook/constructEvent body signature secret)
                    true
                    (catch Exception _ false)))
                accepted-signing-secrets)
      (json/parse-string body true))
    (catch Exception _ nil)))

(defn stripe-webhook [db accepted-signing-secrets]
  ["/webhook/stripe"
   {:post
    {:handler (fn [req]
                (let [signature (get-in req [:headers "stripe-signature"])
                      payload (verified-payload (slurp (:body req)) signature accepted-signing-secrets)]
                  (cond
                    (str/blank? signature)
                    (response/bad-request {:error "Missing Stripe signature"})

                    (nil? payload)
                    (response/bad-request {:error "Invalid Stripe signature"})

                    :else
                    (let [webhook-type (get payload :type)
                          id (get payload :id)]
                      (prn "Handling" webhook-type)
                      (try
                        (if (slack/event-types webhook-type)
                          (jdbc/with-db-transaction [tx db]
                            (when (not= webhook-type "customer.created")
                              (handle-subscription-event tx payload))
                            (slack/enqueue! tx payload))
                          (prn "Ignoring webhook" {:webhook-type webhook-type}))
                        (response/ok {:type webhook-type
                                      :id id
                                      :status "accepted"})
                        (catch clojure.lang.ExceptionInfo e
                          (if (= ::invalid-subscription (:type (ex-data e)))
                            (response/bad-request {:error "Invalid Stripe subscription identifiers"})
                            (throw e))))))))}}])
