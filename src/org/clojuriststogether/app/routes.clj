(ns org.clojuriststogether.app.routes
  (:require [reitit.ring :as ring]
            [ring.util.http-response :as response]
            [ring.middleware.defaults :as defaults]
            [org.clojuriststogether.app.utils :as utils]
            [org.clojuriststogether.app.template :as template]
            [org.clojuriststogether.app.pages.auth :as pages.auth]
            [reitit.http.interceptors.dev :as interceptors]
            [reitit.ring.middleware.exception]
            [integrant.core :as ig]))

(defmethod ig/init-key :app/handler [_ {:keys [stripe db]}]
  (ring/ring-handler
    (ring/router
      [["" {:middleware [:exceptions
                         [:defaults (-> defaults/site-defaults
                                        ;; TODO: re-enable anti-forgery
                                        (assoc-in [:security :anti-forgery] false))]]}
        [""] {:get {:handler (fn [req] (response/found (utils/route-name->path req :login)))}}
        (pages.auth/auth-routes stripe db)]]
      {:reitit.middleware/registry   {:defaults   {:name ::defaults
                                                   :wrap defaults/wrap-defaults}
                                      :exceptions reitit.ring.middleware.exception/exception-middleware}
       :reitit.interceptor/transform interceptors/print-context-diffs})
    (ring/create-default-handler
      {:not-found (constantly {:status 404 :body "Not Found"})})))
