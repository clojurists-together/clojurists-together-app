(ns org.clojuriststogether.app.routes
  (:require [reitit.ring :as ring]
            [ring.util.http-response :as response]
            [ring.middleware.defaults :as defaults]
            [org.clojuriststogether.app.utils :as utils]
            [org.clojuriststogether.app.pages.auth :as pages.auth]
            [org.clojuriststogether.app.webhooks :as webhooks]
            [org.clojuriststogether.app.middleware :as app.middleware]
            [sentry-clj.ring]
            [reitit.ring.middleware.muuntaja :as muuntaja]

            [reitit.ring.middleware.dev :as dev]
            [reitit.ring.middleware.multipart :as multipart]
            [ring.middleware.anti-forgery]
            [reitit.ring.middleware.parameters :as parameters]
            [reitit.ring.coercion :as coercion]

            [reitit.dev.pretty :as pretty]
            [reitit.coercion.spec]
            [reitit.ring.spec :as spec]
            [integrant.core :as ig]
            [muuntaja.core :as m]))

(defn defaults [store]
  {:cookies true
   :session {:flash true
             :store store
             ;; :strict breaks when clicking links from Gmail as they redirect
             ;; through a google.com/url checking page and this counts as a top-level
             ;; navigation.
             :cookie-attrs {:http-only true, :same-site :lax}}
   :security {:anti-forgery true
              :xss-protection {:enable? true, :mode :block}
              :frame-options :sameorigin
              :content-type-options :nosniff}
   :static {:resources "public"}
   :responses {:not-modified-responses true
               :absolute-redirects true
               :content-types true
               :default-charset "utf-8"}})

(defn get-defaults [profile store]
  (if (= :prod profile)
    (-> (defaults store)
        (assoc-in [:session :cookie-attrs :secure] true)
        (assoc-in [:session :cookie-name] "secure-ring-session")
        (assoc-in [:security :ssl-redirect] true)
        (assoc-in [:security :hsts] true)
        (assoc :proxy true))
    (defaults store)))

(defmethod ig/init-key :app/handler [_ {:keys [stripe db store profile email-service]}]
  (let [routes [["" {:middleware [
                                  :parameters
                                  :format-negotiate
                                  :format-response
                                  :exception
                                  :format-request
                                  :coerce-response
                                  :coerce-request
                                  :multipart
                                  [:defaults (get-defaults profile store)]]}
                 ["/" {:name :home
                       :get {:handler (fn [req] (response/found (utils/login-path req)))}}]
                 (pages.auth/auth-routes stripe db email-service)]
                ;; TODO: middleware for Stripe
                ["" {:middleware [:exception
                                  :format-response
                                  :coerce-response]}
                 (webhooks/stripe-webhook db)]]
        router (ring/router
                 routes
                 ;; TODO: disable diffs in prod
                 {:reitit.middleware/transform (if (= :dev profile)
                                                 dev/print-request-diffs
                                                 identity)
                  :validate spec/validate                   ;; enable spec validation for route data
                  :reitit.middleware/registry {
                                               ;; query-params & form-params
                                               :parameters parameters/parameters-middleware
                                               ;; content-negotiation
                                               :format-negotiate muuntaja/format-negotiate-middleware
                                               ;; encoding response body
                                               :format-response muuntaja/format-response-middleware
                                               ;; exception handling
                                               :exception (app.middleware/exception)
                                               ;; decoding request body
                                               :format-request muuntaja/format-request-middleware
                                               ;; coercing response body
                                               :coerce-response coercion/coerce-response-middleware
                                               ;; coercing request parameters
                                               :coerce-request coercion/coerce-request-middleware
                                               ;; multipart
                                               :multipart multipart/multipart-middleware
                                               ;; defaults
                                               :defaults {:name ::defaults
                                                          :wrap defaults/wrap-defaults}
                                               }
                  :exception pretty/exception
                  :data {:coercion reitit.coercion.spec/coercion
                         :muuntaja m/instance}})]
    (ring/ring-handler
      router
      (ring/create-default-handler
        {:not-found (constantly {:status 404 :body "Not Found"})}))))
