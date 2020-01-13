(ns org.clojuriststogether.app.routes
  (:require [reitit.ring :as ring]
            [ring.util.http-response :as response]
            [ring.middleware.defaults :as defaults]
            [org.clojuriststogether.app.utils :as utils]
            [org.clojuriststogether.app.template :as template]
            [org.clojuriststogether.app.pages.auth :as pages.auth]
            [reitit.http.interceptors.dev :as interceptors]
            [integrant.core :as ig]))

(defmethod ig/init-key :app/handler [_ _]
  (ring/ring-handler
    (ring/router
      [["" {:middleware [[:defaults defaults/site-defaults]]}
        [""] {:get {:handler (fn [req] (response/found (utils/route-name->path req :login)))}}
        (pages.auth/auth-routes)]]
      {:reitit.middleware/registry  {:defaults {:name ::defaults
                                                :wrap defaults/wrap-defaults}}
       :reitit.interceptor/transform interceptors/print-context-diffs})
    (ring/create-default-handler
      {:not-found (constantly {:status 404 :body "Not Found"})})))
