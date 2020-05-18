(ns org.clojuriststogether.app.admin.migrate
  (:require [ragtime.repl :as repl]
            [ragtime.jdbc :as jdbc]
            [org.clojuriststogether.app.server :as server])
  (:gen-class))

(defn load-config [profile]
  {:datastore (jdbc/sql-database (get-in (server/config profile) [:org.clojuriststogether.app.db/hikari-cp :jdbc-url]))
   :migrations (jdbc/load-resources "migrations")})

(defn migrate [profile]
  (prn "Migrating DB" profile)
  (repl/migrate (load-config profile)))

(defn -main [& [profile]]
  (migrate (or (some-> profile keyword)
               :prod)))
