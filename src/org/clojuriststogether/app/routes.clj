(ns org.clojuriststogether.app.routes
  (:require [reitit.ring :as ring]
            [ring.util.response]
            [integrant.core :as ig]))

(defmethod ig/init-key :app/handler [_ _]
  (ring/ring-handler
    (ring/router
      [[""] {:get {:handler (fn [_]
                              {:status 302 :headers {"Location" "/sign-up"}}

                              )}}
       ["/sign-up" {:get {:handler (fn [_] {:status 200 :body "pong!"})}}]
       ["/login" {:get {:handler (fn [_] {:status 200 :body "pong!"})}}]])
    (ring/create-default-handler
      {:not-found (constantly {:status 404 :body "Not Found"})})))
