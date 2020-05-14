(ns org.clojuriststogether.app.routes
  (:require [reitit.ring :as ring]
            [ring.util.http-response :as response]
            [ring.middleware.defaults :as defaults]
            [org.clojuriststogether.app.utils :as utils]
            [org.clojuriststogether.app.pages.auth :as pages.auth]
            [org.clojuriststogether.app.webhooks :as webhooks]
            [ring.middleware.session.memory :as memory-session]
            [reitit.ring.middleware.muuntaja :as muuntaja]
            [reitit.ring.middleware.exception :as exception]
            [reitit.ring.middleware.dev :as dev]
            [reitit.ring.middleware.multipart :as multipart]
            [ring.middleware.anti-forgery]
            [reitit.ring.middleware.parameters :as parameters]
            [reitit.ring.coercion :as coercion]
            [reitit.ring.middleware.exception]
            [reitit.dev.pretty :as pretty]
            [reitit.coercion.spec]
            [reitit.ring.spec :as spec]
            [integrant.core :as ig]
            [muuntaja.core :as m]))

(defmethod ig/init-key :app/handler [_ {:keys [stripe db]}]
  (let [store (memory-session/memory-store)
        routes [["" {:middleware [#_[:defaults defaults/site-defaults]
                                  :parameters
                                  :format-negotiate
                                  :format-response
                                  :exception
                                  :format-request
                                  :coerce-response
                                  :coerce-request
                                  :multipart
                                  [:ring-session {:store store}]
                                  :csrf
                                  ]}
                 ["/" {:get {:handler (fn [req] (response/found (utils/route-name->path req :login)))}}]
                 (pages.auth/auth-routes stripe db)]
                ;; TODO: middleware for Stripe
                ["/webhook/stripe" (webhooks/stripe-webhook db)]]
        router (ring/router
                 routes
                 ;; TODO: disable diffs in prod
                 {:reitit.middleware/transform dev/print-request-diffs ;; pretty diffs
                  :validate spec/validate ;; enable spec validation for route data
                  :reitit.middleware/registry {:defaults {:name ::defaults
                                                          :wrap defaults/wrap-defaults}
                                               ;; query-params & form-params
                                               :parameters parameters/parameters-middleware
                                               ;; content-negotiation
                                               :format-negotiate muuntaja/format-negotiate-middleware
                                               ;; encoding response body
                                               :format-response muuntaja/format-response-middleware
                                               ;; exception handling
                                               :exception exception/exception-middleware
                                               ;; decoding request body
                                               :format-request muuntaja/format-request-middleware
                                               ;; coercing response body
                                               :coerce-response coercion/coerce-response-middleware
                                               ;; coercing request parameters
                                               :coerce-request coercion/coerce-request-middleware
                                               ;; multipart
                                               :multipart multipart/multipart-middleware
                                               ;; ring-session
                                               :ring-session {:name ::session
                                                              :wrap ring.middleware.session/wrap-session}
                                               :csrf {:name ::csrf
                                                      :wrap ring.middleware.anti-forgery/wrap-anti-forgery}
                                               }
                  :exception pretty/exception
                  :data {:coercion reitit.coercion.spec/coercion
                         :muuntaja m/instance}})]
    (ring/ring-handler
      router
      (ring/create-default-handler
        {:not-found (constantly {:status 404 :body "Not Found"})}))))
