(ns org.clojuriststogether.app.sentry
  (:require [integrant.core :as ig]
            [sentry-clj.core :as sentry]))

(defmethod ig/init-key ::sentry [_ {:keys [dsn release]}]
  (when dsn
    (sentry/init! dsn {:release release})))

(defmethod ig/halt-key! ::sentry [_ _]
  (sentry/close!))
