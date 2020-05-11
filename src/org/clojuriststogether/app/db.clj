(ns org.clojuriststogether.app.db
  (:require [integrant.core :as ig]
            [hikari-cp.core :as hikari]
            [ragtime.jdbc]
            [ragtime.repl]))

(defmethod ig/init-key ::hikari-cp
  [_ {:keys [jdbc-url]}]
  {:datasource (hikari/make-datasource {:jdbc-url jdbc-url})})

(defmethod ig/halt-key! ::hikari-cp
  [_ ds]
  (hikari/close-datasource (:datasource ds)))

(defn jdbc-url [url]
  (or (some-> (System/getenv "JDBC_DATABASE_URL"))
      url))
