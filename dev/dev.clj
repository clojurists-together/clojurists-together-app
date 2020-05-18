(ns dev
  (:require
    [integrant.repl :as ig-repl]
    [org.clojuriststogether.app.server]
    [ragtime.repl :as repl]
    [org.clojuriststogether.app.server :as server]
    [org.clojuriststogether.app.admin.migrate :as migrate]))

(ig-repl/set-prep! (fn [] (server/prep :dev)))

(def go ig-repl/go)
(def halt ig-repl/halt)
(def reset ig-repl/reset)

(defn migrate []
  (migrate/migrate :dev))

(defn rollback []
  (repl/rollback (migrate/load-config :dev)))

(comment
  (go)
  (reset)
  (halt))
