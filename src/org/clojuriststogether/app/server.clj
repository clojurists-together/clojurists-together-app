(ns org.clojuriststogether.app.server
  (:require [ring.adapter.jetty :as jetty]
            [integrant.core :as ig]
            [org.clojuriststogether.app.routes]
            [clojure.java.io :as io]))

(defn read-config []
  (-> (ig/read-string (slurp (io/resource "config.edn")))
      (update-in [:app/jetty :port]
                 (fn [port]
                   (or (some-> (System/getenv "PORT") Long/parseLong)
                       port)))))

(defmethod ig/init-key :app/jetty [_ {:keys [port join? handler]}]
  (println "server running in port" port)
  (jetty/run-jetty handler {:port port :join? join?}))

(defmethod ig/halt-key! :app/jetty [_ server]
  (.stop server))

(defn init []
  (ig/init (read-config)))