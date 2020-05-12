(ns dev
  (:require
    [integrant.repl :as ig-repl]
    [org.clojuriststogether.app.server]
    [ragtime.jdbc :as jdbc]
    [ragtime.repl :as repl]
    [org.clojuriststogether.app.server :as server]))

(ig-repl/set-prep! (fn [] (server/prep :dev)))

(def go ig-repl/go)
(def halt ig-repl/halt)
(def reset ig-repl/reset)

(defn load-config []
  {:datastore (jdbc/sql-database (get-in (server/config :dev) [:org.clojuriststogether.app.db/hikari-cp :jdbc-url]))
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

