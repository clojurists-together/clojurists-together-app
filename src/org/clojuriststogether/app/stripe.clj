(ns org.clojuriststogether.app.stripe
  (:require [integrant.core :as ig])
  (:import (com.stripe Stripe)))

(defmethod ig/init-key ::stripe
  [_ {:keys [publishable-key secret-key]}]

  (set! Stripe/apiKey secret-key)
  {:publishable-key publishable-key})
