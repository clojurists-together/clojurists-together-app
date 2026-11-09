(ns org.clojuriststogether.app.middleware
  (:require [reitit.ring.middleware.exception :as exception]
            [reitit.coercion :as coercion]
            [ring.util.http-response :as http-response]
            [ring.util.request :as request]
            [clojure.tools.logging :as log]
            [sentry-clj.core :as sentry]))

(defn report-to-sentry [e req]
  (sentry/send-event
   {:throwable e
    :request {:url (request/request-url req)
              :method (name (:request-method req))
              :data (:params req)
              :query-string (:query-string req "")
              :headers (:headers req)
              :env {:session (pr-str (:session req))
                    "REMOTE_ADDR" (:remote-addr req)}}
    :user {:ip-address (:remote-addr req)}}))

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
  (merge exception/default-handlers
         {::exception/default default-unhandled-exception
          ::coercion/reponse-coercion (create-coercion-handler 500)
          ::exception/wrap wrap-log-error}))

(defn exception []
  (exception/create-exception-middleware (default-handlers)))
