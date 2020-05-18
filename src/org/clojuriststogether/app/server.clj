(ns org.clojuriststogether.app.server
  (:require [ring.adapter.jetty :as jetty]
            [integrant.core :as ig]
            [org.clojuriststogether.app.routes]
            [org.clojuriststogether.app.sessions]
            [aero.core :as aero]
            [clojure.java.io :as io]))

(defmethod aero/reader 'ig/ref
  [_ tag value]
  (ig/ref value))

(defn config [profile]
  (aero/read-config (io/resource "config.edn") {:profile profile}))

(defn prep [profile]
  (let [config (config profile)]
    (ig/load-namespaces config)
    config))

(defmethod ig/init-key :app/jetty [_ {:keys [port join? handler]}]
  (println "server running in port" port)
  (jetty/run-jetty handler {:port port :join? join?}))

(defmethod ig/halt-key! :app/jetty [_ server]
  (.stop server))

(defn init [profile]
  (ig/init (prep profile)))
