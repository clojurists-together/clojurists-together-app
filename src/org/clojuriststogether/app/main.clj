(ns org.clojuriststogether.app.main
  (:gen-class))

(defn -main [& args]
  (println "Starting on" (System/getenv "PORT"))
  )
