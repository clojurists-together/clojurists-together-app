(ns org.clojuriststogether.app.pages.member-list
  (:require [ring.util.http-response :as response]
            [org.clojuriststogether.app.utils :as utils]
            [clojure.java.jdbc :as jdbc]
            [honeysql.core :as sql]))

(def plans-mapping
  {
   ;; Production
   "plan_GYMOB8MprRabK5" "contributing"
   "plan_GYMObrXegHTSlE" "contributing"
   "plan_GYMO2CgIuB3Np5" "contributing"
   "plan_GYMNm9yAidO1vT" "contributing"
   "plan_GYMNXYyH3HcN66" "contributing"
   "plan_GYMNjKYl7lisqd" "contributing"
   "plan_GYMNnnaqsO6SMJ" "contributing"
   "plan_GYMMcAKph9dU4h" "contributing"
   "plan_GYMHqy2AGshlAO" "reduce"
   "plan_GYMGYWlY1YEeXF" "reduce"
   "plan_GYMGm73x4ZiN0f" "map"
   "plan_GYMGklEctvcvl2" "map"
   "plan_GYMFD9fLVTXvNH" "transduce"
   "plan_GYMFuEYbKe7nhC" "transduce"
   "plan_GYMEwKK79GIrt1" "filter"
   "plan_GYMD6AQHDFhQGw" "filter"
   "plan_GYMDwLn4mu4TVa" "into"
   "plan_GYMCd3ySx0VVXt" "into"
   "plan_GYMCg0s8rWxvt3" "cons"
   "plan_GYMBpiJ6waMSee" "cons"
   "plan_GYMBwMeoR5Ry8O" "assoc"
   "plan_GYMBnWZmbhqcHx" "assoc"
   "plan_GYMAh3sq7k89ca" "developer"
   "plan_GYM04c8OhQ3oJB" "developer"

   ;; Sandbox
   "plan_HGLFgYEkItJGf8" "contributing"
   "plan_HGLEqqukKXBaXh" "contributing"
   "plan_GY8nEzNqbLlALp" "developer"
   "plan_GY8ntpqaHEUlza" "developer"
   })

(defn get-members [db]
  (->> {:select [:person_name :organization_name :organization_url
                 :founding_member :member_type :subscription_plan
                 :logo_slug]
        :from [:members]
        :order-by [:member_type :created_at :id]}
       ;; TODO: filter for only active members
       (sql/format)
       (jdbc/query db)
       (map (fn [member]
              (merge
                (if (= "company" (:member_type member))
                  {:name (:organization_name member)
                   :url (:organization_url member)
                   :logo (:logo_slug member)}
                  {:name (:person_name member)})
                {:level (get plans-mapping (:subscription_plan member))
                 :founding (:founding_member member)})))))

(defn member-list-routes [db stripe]
  [["/member-list"
    {:name :member-list
     :get {:handler (fn [req]
                      (->
                        {:members (get-members db)}
                        (cheshire.core/generate-string {:pretty true})
                        (response/ok)
                        (response/content-type "application/json")))}}]])
