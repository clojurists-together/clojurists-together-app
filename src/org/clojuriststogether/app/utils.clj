(ns org.clojuriststogether.app.utils
  (:require [reitit.core :as r]))

(defn route-name->path
  ([request route-name] (route-name->path request route-name nil))
  ([request route-name {:keys [path-params query-params]}]
   (-> (r/match-by-name (::r/router request)
                        route-name
                        (or path-params {}))
       (r/match->path (or query-params {})))))

(defn login-path [req]
  (route-name->path req :login))
