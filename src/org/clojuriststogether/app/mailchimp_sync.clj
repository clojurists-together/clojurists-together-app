(ns org.clojuriststogether.app.mailchimp-sync
  "Works out what should change in Mailchimp to match the members table.

  `plan` is pure: it takes member rows and parsed contacts and returns ops as data,
  derived only from their current state, so a repeat run finds nothing left to do.

  The app wins: Mailchimp only keeps subscription status and email preferences. Each op has
  a :disposition. :apply is safe to run unattended. :review needs a person because the change
  sends an email, is hard to undo, or changes the app's own records."
  (:require [clojure.set :as set]
            [clojure.string :as str]))

(def member-tag "Member")
(def cancelled-tag "Cancelled Membership")
(def member-type-names {"company" "Company" "developer" "Developer"})

(defn- clean [s]
  (when s
    (let [trimmed (str/trim (str s))]
      (when-not (str/blank? trimmed) trimmed))))

(defn- email-key [s]
  (some-> (clean s) str/lower-case))

(defn- name-key [s]
  (some-> (clean s) (str/replace #"\s+" " ") str/lower-case))

(defn- active? [member]
  (some? (clean (:subscription_plan member))))

(defn- app-addresses
  "The member's addresses in order of preference for newsletters."
  [member]
  (keep email-key [(:updates_email member) (:email member)]))

(defn- subscribed? [contact]
  (= "subscribed" (:status contact)))

(defn- tagged? [contact]
  (contains? (:tags contact) member-tag))

(defn- merge-field [contact field]
  (clean (get-in contact [:merge-fields field])))

(defn- person-names [contact]
  (->> [(merge-field contact "FULLNAME")
        (str (merge-field contact "FNAME") " " (merge-field contact "LNAME"))]
       (keep name-key)
       set))

(defn- contact-names [contact]
  (into (person-names contact) (keep name-key [(merge-field contact "COMPNAME")])))

(defn- member-names [member]
  (set (keep name-key [(:person_name member) (:organization_name member)])))

(defn- op
  ([member kind disposition contact]
   (op member kind disposition contact {}))
  ([member kind disposition contact details]
   (merge {:op kind
           :disposition disposition
           :contact (:email contact)
           :member-id (:id member)
           :member-name (or (clean (:organization_name member)) (clean (:person_name member)))}
          details)))

;; Name sync is deliberately skipped for now. Names are only set on new contacts because the
;; welcome emails are the only emails that use them. Differences on existing contacts are expected.
(defn- desired-fields
  "Merge fields for a new contact, for its welcome email."
  [member]
  (cond-> {"FNAME" (clean (:preferred_name member))
           "FULLNAME" (clean (:person_name member))}
    (= "company" (:member_type member)) (assoc "COMPNAME" (clean (:organization_name member)))))

(defn- stripe-id-ops [member contact]
  (let [current (merge-field contact "STRIPE_ID")
        wanted (:stripe_customer_id member)]
    (cond
      (nil? current) [(op member :set-merge-field :apply contact {:field "STRIPE_ID" :from "" :to wanted})]
      (not= current wanted) [(op member :flag :review contact {:reason :stripe-id-conflict :from current :to wanted})])))

(defn- member-type-ops [member contact]
  (let [wanted (member-type-names (:member_type member))]
    (when (and wanted (not= #{wanted} (:member-types contact)))
      [(op member :set-member-type :apply contact {:from (:member-types contact) :to wanted})])))

(defn- email-ops
  [member contact contacts]
  (let [wanted (first (app-addresses member))
        login (email-key (:email member))
        kept (email-key (:email contact))]
    (cond
      (= wanted kept) nil
      (not-any? #(= wanted (email-key (:email %))) contacts)
      [(op member :set-email :apply contact {:from (:email contact) :to wanted})]
      (= kept login)
      [(op member :clear-updates-email :review contact {:from wanted})]
      :else
      [(op member :flag :review contact {:reason :keeper-not-app-address :to wanted})])))

(defn- keeper
  "Never resubscribes anyone: a subscribed contact always beats an unsubscribed one,
  and the app's address only decides between contacts with the same standing."
  [member contacts]
  (let [candidates (or (seq (filter subscribed? contacts)) contacts)
        at-address (fn [address]
                     (some #(when (= address (email-key (:email %))) %) candidates))]
    (or (some at-address (app-addresses member))
        (first (sort-by :email candidates)))))

(defn- duplicate-ops
  "Detaches a member's second contact and leaves it in the audience as a non-member, so an
  unsubscribe stays on record. Archiving only applies to one person getting everything twice."
  [member kept contact]
  (concat
   (when (tagged? contact)
     [(op member :remove-tag :apply contact {:tag member-tag :reason :duplicate-contact})])
   (when (= (merge-field contact "STRIPE_ID") (:stripe_customer_id member))
     [(op member :clear-merge-field :apply contact {:field "STRIPE_ID"})])
   (when (and (subscribed? kept)
              (subscribed? contact)
              (seq (set/intersection (person-names kept) (person-names contact))))
     [(op member :archive-contact :review contact {:reason :same-person-subscribed-twice :kept (:email kept)})])))

(defn- possible-aliases
  "Member-tagged contacts with no member but the same name as this one. The tag moves
  to the member's own contact, so a new contact would probably be a duplicate."
  [member orphans]
  (let [names (member-names member)]
    (filter #(seq (set/intersection names (contact-names %))) orphans)))

(defn- active-member-ops [member kept contacts orphans]
  (concat
   (when-not (tagged? kept)
     [(op member :add-tag :apply kept
          (cond-> {:tag member-tag}
            (seq (possible-aliases member orphans)) (assoc :reason :possible-alias)))])
   (when-not (subscribed? kept)
     [(op member :flag :review kept {:reason :unreachable :status-now (:status kept)})])
   (stripe-id-ops member kept)
   (member-type-ops member kept)
   (email-ops member kept contacts)))

(defn- lapsed-member-ops [member kept contacts active-owned?]
  (when (some #(and (tagged? %) (not (active-owned? %))) contacts)
    (concat
     (when (and (tagged? kept) (not (active-owned? kept)))
       [(op member :remove-tag :apply kept {:tag member-tag :reason :no-plan})])
     (when-not (contains? (:tags kept) cancelled-tag)
       [(op member :add-tag :apply kept {:tag cancelled-tag})]))))

(defn- create-op [member orphans]
  (let [aliases (possible-aliases member orphans)]
    (op member :create-contact :review {:email (first (app-addresses member))}
        (cond-> {:status "subscribed"
                 :tags #{member-tag}
                 :email-types :all
                 :member-type (member-type-names (:member_type member))
                 :merge-fields (assoc (into {} (filter val) (desired-fields member))
                                      "STRIPE_ID" (:stripe_customer_id member))}
          (seq aliases) (assoc :reason :possible-alias :aliases (map :email aliases))))))

(defn- orphan-op [members contact]
  (let [alias-of (->> members
                      (filter active?)
                      (filter #(seq (set/intersection (member-names %) (contact-names contact))))
                      first)]
    (op nil :remove-tag :apply contact
        (cond-> {:tag member-tag
                 :reason :no-app-member
                 :member-name (first (contact-names contact))
                 :other-tags (disj (:tags contact) member-tag)}
          alias-of (assoc :possible-alias-of (:id alias-of))))))

(defn plan
  "Returns the ops that would bring Mailchimp in line with the app.

  Contacts are matched on STRIPE_ID and the member's addresses. Contacts with neither are
  left alone unless they hold the Member tag, which only members may have."
  [members contacts]
  (let [by-email (into {} (map (juxt (comp email-key :email) identity)) contacts)
        by-stripe-id (dissoc (group-by #(merge-field % "STRIPE_ID") contacts) nil)
        linked (fn [member]
                 (distinct (concat (get by-stripe-id (:stripe_customer_id member))
                                   (keep by-email (app-addresses member)))))
        member-contacts (map (juxt identity linked) members)
        owners (reduce (fn [acc [member cs]]
                         (reduce #(update %1 (:email %2) conj member) acc cs))
                       {} member-contacts)
        active-owned? (fn [contact] (some active? (get owners (:email contact))))
        exclusive? (fn [contact] (= 1 (count (get owners (:email contact)))))
        orphans (filter #(and (tagged? %) (not (contains? owners (:email %)))) contacts)
        shared (filter #(> (count (get owners (:email %))) 1) contacts)]
    (vec
     (concat
      (mapcat (fn [[member cs]]
                (cond
                  (seq cs)
                  (let [kept (keeper member cs)]
                    (concat
                     (if (active? member)
                       (active-member-ops member kept cs orphans)
                       (lapsed-member-ops member kept cs active-owned?))
                     (mapcat #(duplicate-ops member kept %)
                             (filter exclusive? (remove #{kept} cs)))))

                  (active? member)
                  [(create-op member orphans)]))
              member-contacts)
      (map #(orphan-op members %) orphans)
      (map (fn [contact]
             (op nil :flag :review contact
                 {:reason :contact-shared-by-members
                  :member-ids (map :id (get owners (:email contact)))}))
           shared)))))

(defn- detail [{:keys [tag field reason]}]
  (->> [tag field (some-> reason name)]
       (remove nil?)
       (str/join " ")
       not-empty))

(defn summarize
  "Counts ops by what they would do, for a report that contains no personal details."
  [ops]
  (->> ops
       (group-by (juxt :disposition :op detail))
       (map (fn [[[disposition kind what] grouped]]
              {:disposition disposition :op kind :detail what :count (count grouped)}))
       (sort-by (juxt :disposition :op (comp str :detail)))
       vec))

(def ^:private notice-text
  "Hi %s,

We've been tidying up our membership records, and our mailing list had a different email address for your Clojurists Together membership.

Mailing list had: %s
Our membership records show: %s
We have updated it to: %s

%s

If this is incorrect, please log in at https://members.clojuriststogether.org/login and update your details. If you can't log in, reply to this email or write to hi@clojuriststogether.org.

Thanks,
Clojurists Together
")

(defn- address-moves
  "How each member's mailing address changes, as [from to] pairs: the contact's own
  address changing, or the Member tag moving to another contact."
  [ops]
  (let [member-ops (group-by :member-id ops)
        alias-sources (group-by :possible-alias-of (filter :possible-alias-of ops))]
    (into {}
          (keep (fn [[member-id own-ops]]
                  (let [changed (first (filter #(= :set-email (:op %)) own-ops))
                        added (first (filter #(and (= :add-tag (:op %)) (= member-tag (:tag %))) own-ops))
                        source (or (first (filter #(= :duplicate-contact (:reason %)) own-ops))
                                   (first (get alias-sources member-id)))]
                    (cond
                      changed [member-id [(:from changed) (:to changed)]]
                      (and added source) [member-id [(:contact source) (:contact added)]]))))
          (dissoc member-ops nil))))

(defn- address-use
  "Says what the address is for, in terms of the member's own settings. Invoices have their
  own line because a member with a separate invoicing email might otherwise worry about them."
  [member]
  (str (if (clean (:updates_email member))
         "This is the News and Surveys email on your membership. We'll use it for news, surveys and member voting."
         "This is the email on your membership. We'll use it for news, surveys and member voting.")
       (when-let [invoicing (clean (:invoicing_email member))]
         (str " Invoices still go to your invoicing email, " invoicing "."))))

(defn notices
  "The one-off email telling a member their mailing address changed. Names aren't included,
  because they don't affect what the member receives. Goes to the login address, the one
  address they can use to correct it."
  [members ops]
  (let [moves (address-moves ops)]
    (vec
     (for [member members
           :let [[from to] (get moves (:id member))]
           :when to]
       {:to (:email member)
        :from "hi@clojuriststogether.org"
        :subject "Your Clojurists Together email address"
        :body (format notice-text
                      (or (clean (:preferred_name member)) "there")
                      from to to
                      (address-use member))}))))
