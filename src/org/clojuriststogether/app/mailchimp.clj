(ns org.clojuriststogether.app.mailchimp
  "Mailchimp audience client. Contacts are addressed by email, which Mailchimp hashes."
  (:require [clj-http.client :as client]
            [clojure.string :as str])
  (:import (java.math BigInteger)
           (java.security MessageDigest)))

(def member-type-category "Member Type")
(def email-types-category "Email Types")

(defn- request [{:keys [api-key]} method path options]
  (let [datacenter (last (str/split api-key #"-"))]
    (:body (client/request (merge {:method method
                                   :url (str "https://" datacenter ".api.mailchimp.com/3.0" path)
                                   :basic-auth ["sync" api-key]
                                   :as :json}
                                  options)))))

(defn- get-json [config path params]
  (request config :get path {:query-params params}))

(defn- send-json [config method path body]
  (request config method path {:content-type :json :form-params body}))

(defn- get-all [config path field params]
  (loop [items []]
    (let [page (get-json config path (assoc params :count 1000 :offset (count items)))
          items (into items (get page field))]
      (if (or (empty? (get page field))
              (>= (count items) (:total_items page)))
        items
        (recur items)))))

(defn- subscriber-hash [email]
  (let [digest (.digest (MessageDigest/getInstance "MD5") (.getBytes (str/lower-case email) "UTF-8"))]
    (format "%032x" (BigInteger. 1 digest))))

(defn- member-path [{:keys [list-id]} email]
  (str "/lists/" list-id "/members/" (subscriber-hash email)))

(defn interest-ids
  "The audience's interests by category title, as {title {name id}}."
  [{:keys [list-id] :as config}]
  (into {}
        (for [category (get-all config (str "/lists/" list-id "/interest-categories") :categories {})]
          [(:title category)
           (into {}
                 (map (juxt :name :id))
                 (get-all config
                          (str "/lists/" list-id "/interest-categories/" (:id category) "/interests")
                          :interests
                          {}))])))

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
  (let [type-names (into {} (map (fn [[type-name id]] [id type-name]))
                         (get (interest-ids config) member-type-category))]
    (->> (get-all config
                  (str "/lists/" list-id "/members")
                  :members
                  {:fields "members.email_address,members.status,members.merge_fields,members.tags,members.interests,total_items"})
         (mapv #(parse-contact type-names %)))))

(defn create-contact! [{:keys [list-id] :as config} interests {:keys [contact status merge-fields tags member-type]}]
  (send-json config :post (str "/lists/" list-id "/members")
             {:email_address contact
              :status status
              :merge_fields merge-fields
              :tags (vec tags)
              :interests (-> (zipmap (vals (get interests email-types-category)) (repeat true))
                             (assoc (get-in interests [member-type-category member-type]) true))}))

(defn set-tag! [config email tag active?]
  (send-json config :post (str (member-path config email) "/tags")
             {:tags [{:name tag :status (if active? "active" "inactive")}]}))

(defn set-merge-field! [config email field value]
  (send-json config :patch (member-path config email) {:merge_fields {field value}}))

(defn set-member-type! [config interests email member-type]
  (send-json config :patch (member-path config email)
             {:interests (into {}
                               (map (fn [[type-name id]] [id (= type-name member-type)]))
                               (get interests member-type-category))}))

(defn set-email! [config email new-email]
  (send-json config :patch (member-path config email) {:email_address new-email}))
