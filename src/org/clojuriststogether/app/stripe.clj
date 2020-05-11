(ns org.clojuriststogether.app.stripe
  (:require [integrant.core :as ig])
  (:import (com.stripe Stripe)))

(defmethod ig/init-key ::stripe
  [_ {:keys [publishable-key private-key]}]
  
  (set! Stripe/apiKey private-key)
  {:publishable-key publishable-key})
