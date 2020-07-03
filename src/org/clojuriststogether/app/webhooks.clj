(ns org.clojuriststogether.app.webhooks
  (:require [ring.util.http-response :as response]
            [clojure.java.jdbc :as jdbc]
            [cheshire.core :as json]
            [honeysql.core :as sql]
            [clojure.java.io :as io]))

(defn handle-subscription-event [db payload]
  ;; Lookup customer from event
  ;; Update subscription_plan, or set it to null
  ;; TODO: handle race conditions where new subscription is created and then old one is deleted
  (let [object (get-in payload [:data :object])
        subscription-id (get object :id)
        customer-id (get object :customer)
        _ (prn "customer" customer-id)
        _ (prn object)
        webhook-type (get payload :type)
        plan-id (if (= webhook-type "customer.subscription.deleted")
                  nil
                  (get-in object [:plan :id]))]
    (->> {:update :members
          :set {:subscription_id subscription-id
                :subscription_plan plan-id}
          :where [:= :stripe_customer_id customer-id]}
         (sql/format)
         (jdbc/execute! db))))

(defn stripe-webhook [db]
  ["/webhook/stripe"
   {:post
    {:handler (fn [req]
                (let [payload (json/parse-stream (io/reader (:body req)) true)
                      webhook-type (get payload :type)
                      id (get payload :id)]
                  (prn "Handling" webhook-type)
                  (case webhook-type
                    ("customer.subscription.created" "customer.subscription.updated" "customer.subscription.deleted")
                    (handle-subscription-event db payload)



                    (prn "Ignoring webhook" {:webhook-type webhook-type}))
                  (response/ok {:type webhook-type
                                :id id
                                :status "accepted"})))}}])
