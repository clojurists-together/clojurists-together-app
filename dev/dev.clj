(ns dev
  (:require
    [integrant.repl :as ig-repl]
    [org.clojuriststogether.app.server]
    [ragtime.jdbc :as jdbc]
    [ragtime.repl :as repl]
    [org.clojuriststogether.app.db :as db]))

(ig-repl/set-prep! org.clojuriststogether.app.server/read-config)

(def go ig-repl/go)
(def halt ig-repl/halt)
(def reset ig-repl/reset)

(defn load-config []
  {:datastore  (jdbc/sql-database (db/jdbc-url "jdbc:postgresql:clojurists_together_dev"))
   :migrations (jdbc/load-resources "migrations")})

(defn migrate []
  (prn "Migrating DB")
  (repl/migrate (load-config)))

(defn rollback []
  (repl/rollback (load-config)))

(comment
  (go)
  (reset)
  (halt))

