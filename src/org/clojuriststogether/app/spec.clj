(ns org.clojuriststogether.app.spec
  (:require [clojure.spec.alpha :as s]
            [clojure.string :as str]))

(defn email? [s]
  (and (string? s)
       (some? (re-matches #".+@.+\..+" s))))

(s/def ::email email?)

(defn price-id? [id]
  (and (string? id)
       (not (str/blank? id))))

(s/def ::plan-id
  (s/and price-id?
         (fn [id]
           (some (fn [prefix]
                   (and (str/starts-with? id prefix)
                        (not (str/blank? (subs id (count prefix))))))
                 ["price_" "plan_"]))))
