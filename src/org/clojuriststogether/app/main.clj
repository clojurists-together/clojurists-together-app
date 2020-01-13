(ns org.clojuriststogether.app.main
  (:require [org.clojuriststogether.app.server :as server])
  (:gen-class)
  (:import (com.stripe Stripe)))

(defn -main [& args]
  (server/init))
