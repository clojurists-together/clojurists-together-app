(ns org.clojuriststogether.app.main
  (:require [org.clojuriststogether.app.server :as server])
  (:gen-class))

(defn -main [& args]
  (server/init))
