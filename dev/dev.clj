(ns dev
  (:require
    [integrant.repl :as ig-repl]
    [org.clojuriststogether.app.server]))

(ig-repl/set-prep! org.clojuriststogether.app.server/read-config)

(def go ig-repl/go)
(def halt ig-repl/halt)
(def reset ig-repl/reset)

(comment
  (go)
  (reset)
  (halt))
