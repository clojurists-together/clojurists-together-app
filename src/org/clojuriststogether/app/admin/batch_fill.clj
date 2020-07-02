(ns org.clojuriststogether.app.admin.batch-fill
  (:require [honeysql.core :as sql]
            [clojure.java.jdbc :as jdbc])
  (:import (com.stripe.model Customer Subscription)
           (com.stripe Stripe)))

(defn create-customer-and-subscription [db {:keys [id email name subscription_plan]}]
  (let [customer (Customer/create {"email" email
                                   "name" name})
        customer-id (.getId customer)
        _ (Subscription/create {"customer" customer-id
                                "items" [{"plan" subscription_plan}]
                                "trial_period_days" 30})
        member (->> {:update :members
                     :set {:stripe_customer_id customer-id}
                     :where [:= :id id]}
                    (sql/format)
                    (jdbc/execute! db))]))

(defn find-all-backfills [db]
  (->> {:select [:*]
        :from [:members]
        :where [:= :stripe_customer_id "missing"]}
       (sql/format)
       (jdbc/query db)))

(defn create-stripe-customers [db]
  ; heroku run echo \$JDBC_DATABASE_URL
  ;(set! Stripe/apiKey "")

  (let [members (find-all-backfills db)]
    (doseq [member members]
      (prn "Updating" (:email member))
      (create-customer-and-subscription db member))))

(defn backfill-subscriptions [db]
  (doseq [subscription (take 3 (.autoPagingIterable (Subscription/list {})))]


    (prn subscription)
    )
  )
