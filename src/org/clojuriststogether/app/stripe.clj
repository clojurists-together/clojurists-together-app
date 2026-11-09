(ns org.clojuriststogether.app.stripe
  (:require [integrant.core :as ig])
  (:import (com.stripe Stripe)))

(defmethod ig/init-key ::stripe
  [_ {:keys [secret-key]}]
  (set! Stripe/apiKey secret-key)
  nil)
