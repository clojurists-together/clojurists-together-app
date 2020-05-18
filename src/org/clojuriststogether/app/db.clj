(ns org.clojuriststogether.app.db
  (:require [integrant.core :as ig]
            [hikari-cp.core :as hikari]
            [cheshire.core :as json]
            [ragtime.jdbc]
            [ragtime.repl]
            [honeysql.format :as f]
            [honeysql.helpers :as h]
            [clojure.java.jdbc :as jdbc])
  (:import (java.sql Timestamp PreparedStatement)
           (org.postgresql.util PGobject)
           (java.time Instant)))

;; If we need more Postgres specific extensions than this, then we should use
;; https://github.com/nilenso/honeysql-postgres
(defmethod f/format-clause :returning [[_ fields] _]
  (str "RETURNING " (f/comma-join (map f/to-sql fields))))

(f/register-clause! :returning 225)

(h/defhelper returning [m fields]
             (assoc m :returning (h/collify fields)))

(extend-protocol jdbc/IResultSetReadColumn
  Timestamp
  (result-set-read-column [v _ _]
    (.toInstant v))

  PGobject
  (result-set-read-column [pgobj _metadata _index]
    (let [type (.getType pgobj)
          value (.getValue pgobj)]
      (case type
        "json" (json/parse-string value true)
        "jsonb" (json/parse-string value true)
        "citext" (str value)
        value))))

(extend-type Instant
  jdbc/ISQLParameter
  (set-parameter [v ^PreparedStatement stmt idx]
    (.setTimestamp stmt idx (Timestamp/from v))))

(defmethod ig/init-key ::hikari-cp
  [_ {:keys [jdbc-url]}]
  {:datasource (hikari/make-datasource {:jdbc-url jdbc-url})})

(defmethod ig/halt-key! ::hikari-cp
  [_ ds]
  (hikari/close-datasource (:datasource ds)))
