(ns org.clojuriststogether.app.stripe
  (:require [integrant.core :as ig]
            [honeysql.core :as sql]
            [clojure.java.jdbc :as jdbc]
            [cheshire.core :as json])
  (:import (com.stripe Stripe)
           (com.stripe.model Subscription)
           (java.time Instant)))

(defmethod ig/init-key ::stripe
  [_ {:keys [publishable-key secret-key]}]

  (set! Stripe/apiKey secret-key)
  {:publishable-key publishable-key})

(defn insert-subscription [db ^Subscription subscription]
  (let [json-string (.toJson subscription)
        {:keys [id customer status plan start_date created ended_at collection_method] :as parsed} (json/parse-string json-string true)]
    ;; TODO: look up member id

    (->> {:insert-into :subscriptions
          :values [{:id id
                    :customer_id customer
                    :member_id nil
                    :status status
                    :plan_id (:id plan)
                    :plan_nickname (:nickname plan)
                    :product_id (:product plan)
                    :created (some-> created (Instant/ofEpochSecond))
                    :start_date (some-> start_date (Instant/ofEpochSecond))
                    :ended_at (some-> ended_at (Instant/ofEpochSecond))
                    :collection_method collection_method
                    :json_payload (sql/call :cast json-string :jsonb)}]}
         (sql/format)
         (jdbc/execute! db))))
