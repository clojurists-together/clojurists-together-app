(ns org.clojuriststogether.app.stripe-cache-test
  (:require [clojure.test :refer :all]
            [org.clojuriststogether.app.stripe :as stripe]
            [org.clojuriststogether.app.pages.cashflow :as page])
  (:import (java.time Instant)))

(defn request [cache key loader now]
  (try (stripe/cached-snapshot cache key loader now)
       (catch clojure.lang.ExceptionInfo e (:type (ex-data e)))))

(deftest cold-load-is-nonblocking-and-shared
  (let [cache (atom nil) started (promise) release (promise) finished (promise)
        calls (atom 0) now (Instant/now)
        data {:as-of now :actuals [1]}
        loader #(do (swap! calls inc) (deliver started true) @release data)]
    (add-watch cache :finished (fn [_ _ _ state] (when (:data state) (deliver finished true))))
    (try
      (is (= ::stripe/loading (deref (future (request cache :a loader now)) 1000 :blocked)))
      (is (= true (deref started 1000 :timeout)))
      (is (= ::stripe/loading (deref (future (request cache :a loader now)) 1000 :blocked)))
      (is (= ::stripe/loading (request cache :different-key loader now)))
      (is (= 1 @calls))
      (finally (deliver release true)))
    (is (= true (deref finished 1000 :timeout)))
    (is (= data (select-keys (request cache :a loader now) [:as-of :actuals])))
    (is (= 1 @calls))))

(deftest stale-data-survives-failed-refresh-with-backoff
  (let [now (Instant/now) finished (promise) calls (atom 0)
        data {:as-of (.minusSeconds now 600) :actuals [123]}
        cache (atom {:key :a :data data :expires-at (.minusSeconds now 1)
                     :usable-until (.plusSeconds now 1000)})
        loader #(do (swap! calls inc) (throw (ex-info "unavailable" {})))]
    (add-watch cache :failed (fn [_ _ _ state] (when (:error state) (deliver finished true))))
    (is (= [123] (:actuals (request cache :a loader now))))
    (is (= true (deref finished 1000 :timeout)))
    (let [result (request cache :a loader now)]
      (is (:refresh-failed? result))
      (is (= (:as-of data) (:as-of result))))
    (is (= 1 @calls))
    (swap! cache assoc :usable-until (.minusSeconds now 1))
    (is (= ::stripe/loading (request cache :a loader now)))
    (is (= 1 @calls))))

(deftest loading-page-and-export-do-not-wait-or-download-incomplete-data
  (with-redefs [stripe/snapshot (fn [_] (throw (ex-info "Loading" {:type ::stripe/loading})))]
    (let [page ((page/handler {} false) {:query-params {}})
          export ((page/handler {} true) {:query-params {}})]
      (is (= 202 (:status page)))
      (is (= "5" (get-in page [:headers "Refresh"])))
      (is (= 503 (:status export)))
      (is (nil? (get-in export [:headers "Content-Disposition"])))
      (is (nil? (get-in export [:headers "Refresh"]))))))
