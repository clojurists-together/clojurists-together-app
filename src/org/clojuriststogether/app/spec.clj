(ns org.clojuriststogether.app.spec
  (:require [clojure.spec.alpha :as s]
            [clojure.string :as str]))

(defn email? [s]
  (and (string? s)
       (some? (re-matches #".+@.+\..+" s))))

(s/def ::email email?)

(s/def ::plan-id (s/and string?
                        #(str/starts-with? % "plan_")))
