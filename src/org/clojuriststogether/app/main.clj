(ns org.clojuriststogether.app.main
  (:require [org.clojuriststogether.app.server :as server])
  (:gen-class))

(defn -main [& [profile]]
  (server/init (keyword profile)))
