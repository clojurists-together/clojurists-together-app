(ns org.clojuriststogether.app.stripe
  (:require [integrant.core :as ig])
  (:import (com.stripe Stripe)
           (com.stripe.model Customer)
           (com.stripe.net RequestOptions)))

(defmethod ig/init-key ::stripe
  [_ {:keys [publishable-key secret-key]}]

  (set! Stripe/apiKey secret-key)
  {:publishable-key publishable-key})

(defn customers
  "Every customer's id and email, using the given key rather than the app's own."
  [api-key]
  (let [options (-> (RequestOptions/builder) (.setApiKey api-key) .build)
        ^java.util.Map params {"limit" 100}]
    (->> (.autoPagingIterable (Customer/list params options))
         (mapv (fn [^Customer customer]
                 {:id (.getId customer)
                  :email (.getEmail customer)})))))
