(ns org.clojuriststogether.app.routes
  (:require [reitit.ring :as ring]
            [ring.util.http-response :as response]
            [ring.middleware.defaults :as defaults]
            [org.clojuriststogether.app.utils :as utils]
            [org.clojuriststogether.app.template :as template]
            [integrant.core :as ig]))

(defmethod ig/init-key :app/handler [_ _]
  (ring/ring-handler
    (ring/router
      [["" {:middleware [[:defaults defaults/site-defaults]]}
        [""] {:get {:handler (fn [req]
                               (prn req)
                               (prn (utils/route-name->path req :login))
                               (response/found (utils/route-name->path req :login)))}}
        ["/sign-up" {:name :sign-up
                     :get  {:handler (fn [req]
                                       (-> (response/ok (template/template req))
                                           (response/content-type "text/html")))}}]
        ["/login" {:name :login
                   :get {:handler (fn [_] {:status 200 :body "pong!"})}}]]]
      {:reitit.middleware/registry {:defaults {:name ::defaults
                                               :wrap defaults/wrap-defaults}}})
    (ring/create-default-handler
      {:not-found (constantly {:status 404 :body "Not Found"})})))
