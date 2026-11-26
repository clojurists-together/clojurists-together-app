(ns org.clojuriststogether.app.stripe
  (:require [integrant.core :as ig]
            [cheshire.core :as json])
  (:import (com.stripe Stripe)
           (com.stripe.model Customer Price Product)
           (com.stripe.net RequestOptions)))

(defmethod ig/init-key ::stripe
  [_ {:keys [secret-key]}]
  (set! Stripe/apiKey secret-key)
  nil)

(defn- request-options [api-key]
  (-> (RequestOptions/builder) (.setApiKey api-key) .build))

(defn customers
  "Every customer's id and email, using the given key rather than the app's own."
  [api-key]
  (let [^java.util.Map params {"limit" 100}]
    (->> (.autoPagingIterable (Customer/list params ^RequestOptions (request-options api-key)))
         (mapv (fn [^Customer customer]
                 {:id (.getId customer)
                  :email (.getEmail customer)})))))

(defn set-customer-email! [api-key customer-id email]
  (let [^RequestOptions options (request-options api-key)
        ^java.util.Map params {"email" email}
        ^Customer customer (Customer/retrieve ^String customer-id options)]
    (.update customer params options)))

(defn notification-object
  "Fetches display details with the SDK-pinned API version."
  [kind id]
  (json/parse-string (.toJson (case kind
                                :customer (Customer/retrieve ^String id)
                                :price (Price/retrieve ^String id)
                                :product (Product/retrieve ^String id))) true))
