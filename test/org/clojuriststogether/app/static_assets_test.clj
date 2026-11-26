(ns org.clojuriststogether.app.static-assets-test
  (:require [clojure.test :refer :all]
            [integrant.core :as ig]
            [ring.middleware.session.memory :as memory]
            [org.clojuriststogether.app.routes]))

(deftest public-assets-are-served-outside-matched-routes
  (let [handler (ig/init-key :app/handler {:profile :prod :db {} :store (memory/memory-store)})
        request (fn [path method] (handler {:uri path :request-method method :scheme :https
                                            :server-name "members.clojuriststogether.org" :server-port 443
                                            :headers {}}))]
    (is (= 404 (:status (request "/css/missing.css" :get))))
    (is (= 404 (:status (request "/config.edn" :get))))
    (is (= 404 (:status (request "/../config.edn" :get))))
    (is (= 404 (:status (request "/admin/cashflow" :get))))))
