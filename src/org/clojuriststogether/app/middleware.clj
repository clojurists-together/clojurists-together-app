(ns org.clojuriststogether.app.middleware
  (:require [reitit.ring.middleware.exception :as exception]
            [reitit.coercion :as coercion]
            [sentry-clj.ring :as sentry.ring]
            [clojure.tools.logging :as log]
            [sentry-clj.core :as sentry]))

(def version (delay (System/getenv "HEROKU_RELEASE_VERSION")))

(defn report-to-sentry [e req]
  (-> (sentry.ring/request->event req e)
      (assoc :release @version)
      (sentry/send-event)))

(defn default-unhandled-exception [e req]
  (report-to-sentry e req)
  {:status 500
   :body {:type "exception"
          :class (.getName (.getClass e))}})

(defn create-coercion-handler
  "Creates a coercion exception handler."
  [status]
  (fn [e req]
    (report-to-sentry e req)
    {:status status
     :body (coercion/encode-error (ex-data e))}))

(defn wrap-log-error [handler ^Throwable e {:keys [uri request-method] :as req}]
  (log/error e request-method (pr-str uri))
  (handler e req))

(defn default-handlers
  []
  {::exception/default default-unhandled-exception
   ::coercion/reponse-coercion (create-coercion-handler 500)
   ::exception/wrap wrap-log-error})

(defn exception []
  (exception/create-exception-middleware (default-handlers)))
