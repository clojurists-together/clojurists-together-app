(ns org.clojuriststogether.app.mailchimp
  "Read-only Mailchimp client. It only issues GET requests."
  (:require [clj-http.client :as client]
            [clojure.string :as str]))

(def member-type-category "Member Type")

(defn- get-json [{:keys [api-key]} path params]
  (let [datacenter (last (str/split api-key #"-"))]
    (:body (client/get (str "https://" datacenter ".api.mailchimp.com/3.0" path)
                       {:basic-auth ["sync" api-key]
                        :as :json
                        :query-params params}))))

(defn- get-all [config path field params]
  (loop [items []]
    (let [page (get-json config path (assoc params :count 1000 :offset (count items)))
          items (into items (get page field))]
      (if (or (empty? (get page field))
              (>= (count items) (:total_items page)))
        items
        (recur items)))))

(defn- member-type-ids
  "Maps the audience's interest ids to their names, for the hidden Member Type group."
  [{:keys [list-id] :as config}]
  (let [categories (get-all config (str "/lists/" list-id "/interest-categories") :categories {})
        category (first (filter #(= member-type-category (:title %)) categories))
        interests (get-all config
                           (str "/lists/" list-id "/interest-categories/" (:id category) "/interests")
                           :interests
                           {})]
    (into {} (map (juxt :id :name)) interests)))

(defn parse-contact [type-names contact]
  {:email (:email_address contact)
   :status (:status contact)
   :merge-fields (into {} (map (fn [[k v]] [(name k) v])) (:merge_fields contact))
   :tags (set (map :name (:tags contact)))
   :member-types (->> (:interests contact)
                      (filter val)
                      (keep (fn [[id _]] (get type-names (name id))))
                      set)})

(defn contacts [{:keys [list-id] :as config}]
  (let [type-names (member-type-ids config)]
    (->> (get-all config
                  (str "/lists/" list-id "/members")
                  :members
                  {:fields "members.email_address,members.status,members.merge_fields,members.tags,members.interests,total_items"})
         (mapv #(parse-contact type-names %)))))
