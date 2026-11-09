(ns org.clojuriststogether.app.mailchimp-sync-test
  (:require [clojure.test :refer [deftest is testing]]
            [org.clojuriststogether.app.mailchimp-sync :as sync]))

(def member
  {:id 1
   :email "ada@example.com"
   :updates_email nil
   :person_name "Ada Lovelace"
   :preferred_name "Ada"
   :member_type "developer"
   :stripe_customer_id "cus_ada"
   :subscription_plan "plan_a"
   :organization_name nil})

(def contact
  {:email "ada@example.com"
   :status "subscribed"
   :merge-fields {"FNAME" "Ada" "LNAME" "Lovelace" "FULLNAME" "Ada Lovelace" "COMPNAME" "" "STRIPE_ID" "cus_ada"}
   :tags #{"Member"}
   :member-types #{"Developer"}})

(defn ops-for [members contacts & {:keys [op]}]
  (cond->> (sync/plan members contacts)
    op (filter #(= op (:op %)))))

(defn one-op [members contacts op]
  (let [ops (ops-for members contacts :op op)]
    (is (= 1 (count ops)) (str "expected exactly one " op))
    (first ops)))

(deftest in-sync-member-needs-nothing
  (is (empty? (sync/plan [member] [contact]))))

(deftest repeat-run-converges
  (testing "an unsynced contact stops producing changes once its fields and tags match"
    (let [stale (-> contact
                    (assoc :tags #{} :member-types #{})
                    (assoc-in [:merge-fields "FULLNAME"] ""))]
      (is (seq (sync/plan [member] [stale])))
      (is (empty? (sync/plan [member] [contact]))))))

(deftest new-paying-member-without-contact
  (let [op (one-op [member] [] :create-contact)]
    (is (= "ada@example.com" (:contact op)))
    (is (= "subscribed" (:status op)))
    (is (= #{"Member"} (:tags op)))
    (is (= :all (:email-types op)))
    (is (= "Developer" (:member-type op)))
    (is (= {"FNAME" "Ada" "FULLNAME" "Ada Lovelace" "STRIPE_ID" "cus_ada"} (:merge-fields op)))
    (is (= :apply (:disposition op)))))

(deftest abandoned-signup-is-ignored
  (is (empty? (sync/plan [(assoc member :subscription_plan nil)] []))))

(deftest non-member-contacts-are-untouched
  (is (empty? (sync/plan [] [(assoc contact :tags #{"no_card"} :email "reader@example.com")]))))

(deftest member-tag-follows-the-plan
  (testing "a paying member gains the tag"
    (let [op (one-op [member] [(assoc contact :tags #{"no_card"})] :add-tag)]
      (is (= "Member" (:tag op)))
      (is (= :apply (:disposition op)))))
  (testing "a member who lost their plan loses the tag and is marked cancelled"
    (let [ops (ops-for [(assoc member :subscription_plan nil)] [contact])]
      (is (= #{[:remove-tag "Member"] [:add-tag "Cancelled Membership"]}
             (set (map (juxt :op :tag) ops))))
      (is (every? #(= :apply (:disposition %)) ops))))
  (testing "past cancellations with no tag are not backfilled"
    (is (empty? (sync/plan [(assoc member :subscription_plan nil)] [(assoc contact :tags #{})])))))

(deftest names-are-not-synced-to-existing-contacts
  (doseq [contact-fields [{"FNAME" "" "FULLNAME" ""}
                          {"FNAME" "Different" "FULLNAME" "Someone Else" "LNAME" "Else"}]]
    (is (empty? (sync/plan [member] [(update contact :merge-fields merge contact-fields)])))))

(deftest new-company-contact-gets-its-name-for-the-welcome-email
  (let [company (assoc member :member_type "company" :organization_name "Acme")
        op (one-op [company] [] :create-contact)]
    (is (= "Acme" (get-in op [:merge-fields "COMPNAME"])))
    (is (= "Company" (:member-type op)))))

(deftest member-type-group
  (let [op (one-op [member] [(assoc contact :member-types #{"Company" "Developer"})] :set-member-type)]
    (is (= [#{"Company" "Developer"} "Developer" :apply] ((juxt :from :to :disposition) op)))))

(deftest stripe-id
  (testing "fills when blank"
    (let [op (one-op [member] [(assoc-in contact [:merge-fields "STRIPE_ID"] "")] :set-merge-field)]
      (is (= ["STRIPE_ID" "cus_ada" :apply] ((juxt :field :to :disposition) op)))))
  (testing "never overwrites a different customer's id"
    (let [ops (sync/plan [member] [(assoc-in contact [:merge-fields "STRIPE_ID"] "cus_other")])]
      (is (= [[:flag :stripe-id-conflict]] (map (juxt :op :reason) ops))))))

(deftest subscription-status-is-never-changed
  (let [unsubscribed (assoc contact :status "unsubscribed")
        ops (sync/plan [member] [unsubscribed])]
    (is (= [[:flag :unreachable]] (map (juxt :op :reason) ops)))))

(deftest app-address-is-the-newsletter-address
  (testing "the app's address replaces a different one on the contact"
    (let [app (assoc member :email "new@example.com" :updates_email nil)
          op (one-op [app] [contact] :set-email)]
      (is (= ["ada@example.com" "new@example.com" :apply] ((juxt :from :to :disposition) op)))))
  (testing "updates_email wins over the login email when choosing a contact"
    (let [app (assoc member :updates_email "news@example.com")
          ops (sync/plan [app] [(assoc contact :email "news@example.com")])]
      (is (empty? ops)))))

(defn pair [keep-status other-status & {:keys [same-name?] :or {same-name? true}}]
  [(assoc contact :email "ada@example.com" :status keep-status)
   (cond-> (assoc contact :email "old@example.com" :status other-status)
     (not same-name?) (update :merge-fields assoc "FULLNAME" "Someone Else" "FNAME" "Someone" "LNAME" "Else"))])

(deftest keep-rule-for-two-contacts
  (testing "both subscribed keeps the app's address and detaches the other"
    (let [ops (sync/plan [member] (pair "subscribed" "subscribed" :same-name? false))]
      (is (= #{[:remove-tag "old@example.com"] [:clear-merge-field "old@example.com"]}
             (set (map (juxt :op :contact) ops))))
      (is (= "STRIPE_ID" (:field (first (filter #(= :clear-merge-field (:op %)) ops)))))))
  (testing "the same person subscribed twice has the other contact archived, pending review"
    (let [op (one-op [member] (pair "subscribed" "subscribed") :archive-contact)]
      (is (= ["old@example.com" :review] ((juxt :contact :disposition) op)))))
  (testing "one subscribed contact is kept even when it isn't the app's address"
    (let [ops (sync/plan [member] (pair "unsubscribed" "subscribed"))]
      (is (= #{[:remove-tag "ada@example.com"] [:clear-merge-field "ada@example.com"] [:flag "old@example.com"]}
             (set (map (juxt :op :contact) ops))))
      (is (= :keeper-not-app-address (:reason (first (filter #(= :flag (:op %)) ops)))))))
  (testing "neither subscribed keeps the app's address and flags the member as unreachable"
    (let [ops (sync/plan [member] (pair "unsubscribed" "cleaned"))]
      (is (= #{[:flag "ada@example.com"] [:remove-tag "old@example.com"] [:clear-merge-field "old@example.com"]}
             (set (map (juxt :op :contact) ops))))
      (is (= :unreachable (:reason (first (filter #(= :flag (:op %)) ops)))))))
  (testing "nobody is resubscribed or unsubscribed by the cleanup"
    (doseq [[keep other] [["subscribed" "subscribed"] ["unsubscribed" "subscribed"] ["unsubscribed" "cleaned"]]]
      (is (not-any? :status (sync/plan [member] (pair keep other)))))))

(deftest tag-moves-to-the-kept-contact
  (let [ops (sync/plan [member]
                       [(assoc contact :tags #{})
                        (assoc contact :email "old@example.com" :status "unsubscribed")])]
    (is (= #{[:add-tag "ada@example.com"] [:remove-tag "old@example.com"]}
           (set (map (juxt :op :contact) (filter :tag ops)))))))

(deftest unsubscribed-newsletter-address-clears-updates-email
  (let [app (assoc member :updates_email "news@example.com")
        op (one-op [app]
                   [contact (assoc contact :email "news@example.com" :status "unsubscribed" :tags #{})]
                   :clear-updates-email)]
    (is (= ["news@example.com" :review] ((juxt :from :disposition) op)))))

(deftest member-tag-with-no-member
  (let [op (one-op [] [(assoc contact :email "board@example.com")] :remove-tag)]
    (is (= [:no-app-member :apply] ((juxt :reason :disposition) op)))))

(deftest possible-alias-by-name
  (let [alias-contact (assoc contact :email "alias@example.com"
                             :merge-fields {"FULLNAME" "ada lovelace" "STRIPE_ID" ""})]
    (testing "the tag leaves the other contact"
      (let [op (first (filter #(= "alias@example.com" (:contact %)) (sync/plan [member] [(assoc contact :tags #{}) alias-contact])))]
        (is (= :remove-tag (:op op)))
        (is (= :apply (:disposition op)))
        (is (= 1 (:possible-alias-of op)))))
    (testing "and moves to the member's own contact"
      (let [op (one-op [member] [(assoc contact :tags #{}) alias-contact] :add-tag)]
        (is (= [:apply :possible-alias] ((juxt :disposition :reason) op)))))
    (testing "no new contact is created without review when one may already exist"
      (let [op (one-op [member] [alias-contact] :create-contact)]
        (is (= [:review :possible-alias] ((juxt :disposition :reason) op)))))))

(deftest contact-claimed-by-two-members
  (let [other (assoc member :id 2 :email "other@example.com" :stripe_customer_id "cus_other" :updates_email "ada@example.com")
        ops (sync/plan [member other] [contact])]
    (is (some #(= [:flag :contact-shared-by-members] ((juxt :op :reason) %)) ops))))

(deftest summary-counts-changes-by-disposition
  (let [summary (sync/summarize (sync/plan [member] [(assoc contact :tags #{})]))]
    (is (= [{:disposition :apply :op :add-tag :detail "Member" :count 1}] summary))))

(deftest notices-cover-address-changes-only
  (testing "a different address on the contact"
    (let [app (assoc member :email "new@example.com")
          [notice :as all] (sync/notices [app] (sync/plan [app] [contact]))]
      (is (= 1 (count all)))
      (is (= "new@example.com" (:to notice)))
      (is (= "hi@clojuriststogether.org" (:from notice)))
      (is (re-find #"Mailing list had: ada@example.com" (:body notice)))
      (is (re-find #"Our membership records show: new@example.com" (:body notice)))
      (is (re-find #"We have updated it to: new@example.com" (:body notice)))
      (is (re-find #"Hi Ada," (:body notice)))))
  (testing "the Member tag moving between a member's contacts"
    (let [contacts [(assoc contact :tags #{})
                    (assoc contact :email "old@example.com" :status "unsubscribed")]
          [notice] (sync/notices [member] (sync/plan [member] contacts))]
      (is (re-find #"Mailing list had: old@example.com" (:body notice)))
      (is (re-find #"We have updated it to: ada@example.com" (:body notice)))))
  (testing "the Member tag moving from a contact with the same name"
    (let [alias-contact (assoc contact :email "alias@example.com" :merge-fields {"FULLNAME" "Ada Lovelace"})
          [notice] (sync/notices [member] (sync/plan [member] [(assoc contact :tags #{}) alias-contact]))]
      (is (re-find #"Mailing list had: alias@example.com" (:body notice)))))
  (testing "tag, name and group changes alone send nothing"
    (let [contacts [(assoc contact :tags #{} :member-types #{}
                           :merge-fields {"FULLNAME" "Different" "STRIPE_ID" "cus_ada"})]]
      (is (empty? (sync/notices [member] (sync/plan [member] contacts)))))))

(deftest notice-layout-and-copy
  (let [notice-for (fn [app]
                     (:body (first (sync/notices [app] (sync/plan [app] [contact])))))
        changed (assoc member :email "new@example.com")]
    (testing "each address is on its own line"
      (is (re-find #"\nMailing list had: ada@example.com\nOur membership records show: new@example.com\nWe have updated it to: new@example.com\n"
                   (notice-for changed))))
    (testing "without separate emails it refers to the membership email"
      (let [body (notice-for changed)]
        (is (re-find #"This is the email on your membership" body))
        (is (not (re-find #"Invoices" body)))))
    (testing "a newsletter email is named as one"
      (is (re-find #"This is the News and Surveys email on your membership"
                   (notice-for (assoc changed :updates_email "news@example.com")))))
    (testing "a separate invoicing email is mentioned so nobody worries about invoices"
      (is (re-find #"Invoices still go to your invoicing email, accounts@example.com"
                   (notice-for (assoc changed :invoicing_email "accounts@example.com")))))))

(deftest a-new-address-gets-its-own-contact-when-the-old-one-cant-receive-email
  (doseq [status ["unsubscribed" "cleaned"]]
    (let [app (assoc member :email "new@example.com")
          ops (sync/plan [app] [(assoc contact :status status)])
          create (first (filter #(= :create-contact (:op %)) ops))]
      (testing "the new address is subscribed"
        (is (= ["new@example.com" "subscribed" "ada@example.com" :apply]
               ((juxt :contact :status :replaces :disposition) create)))
        (is (contains? (:tags create) "Member")))
      (testing "the old contact keeps its status and is detached"
        (is (= #{[:remove-tag "ada@example.com"] [:clear-merge-field "ada@example.com"]}
               (set (map (juxt :op :contact) (remove #{create} ops)))))
        (is (not-any? #(= :set-email (:op %)) ops)))
      (testing "the member is told"
        (let [[notice] (sync/notices [app] ops)]
          (is (= "new@example.com" (:to notice)))
          (is (re-find #"Mailing list had: ada@example.com" (:body notice)))
          (is (re-find #"We have updated it to: new@example.com" (:body notice))))))))

(deftest an-unsubscribed-address-that-is-unchanged-stays-unsubscribed
  (let [ops (sync/plan [member] [(assoc contact :status "unsubscribed")])]
    (is (not-any? #(= :create-contact (:op %)) ops))))

(deftest safety-stop
  (let [tagged (for [n (range 30)] (assoc contact :email (str "orphan" n "@example.com")))]
    (testing "a large removal of the Member tag stops the run"
      (let [ops (sync/plan [member] (cons contact tagged))]
        (is (re-find #"Would remove the Member tag 30 times" (sync/unsafe-reason [member] ops)))))
    (testing "an empty or planless members table stops the run"
      (is (= "No members with a plan" (sync/unsafe-reason [] [])))
      (is (= "No members with a plan" (sync/unsafe-reason [(assoc member :subscription_plan nil)] []))))
    (testing "an ordinary run goes ahead"
      (is (nil? (sync/unsafe-reason [member] (sync/plan [member] [contact])))))))
