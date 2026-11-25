(ns org.clojuriststogether.app.admin.mailchimp-dry-run
  "Prints what a Mailchimp and Stripe sync would change. It reads the members table in a
  read-only transaction, only sends GET requests, and has no way to apply or send anything."
  (:require [clojure.java.jdbc :as jdbc]
            [clojure.string :as str]
            [honey.sql :as sql]
            [org.clojuriststogether.app.db]
            [org.clojuriststogether.app.mailchimp :as mailchimp]
            [org.clojuriststogether.app.mailchimp-sync :as mailchimp-sync]
            [org.clojuriststogether.app.stripe :as stripe]
            [org.clojuriststogether.app.stripe-sync :as stripe-sync])
  (:gen-class))

(defn required-env [& names]
  (or (some #(System/getenv %) names)
      (throw (ex-info (str "Set " (str/join " or " names)) {:names names}))))

(defn members [jdbc-url]
  (jdbc/with-db-transaction [tx {:connection-uri jdbc-url} {:read-only? true}]
    (let [read-only (:transaction_read_only (first (jdbc/query tx "SHOW transaction_read_only")))]
      (when-not (= "on" read-only)
        (throw (ex-info "Expected a read-only transaction" {:transaction_read_only read-only}))))
    (->> {:select [:id :email :updates_email :invoicing_email :person_name :preferred_name :member_type
                   :stripe_customer_id :subscription_plan :organization_name]
          :from [:members]
          :order-by [:id]}
         sql/format
         (jdbc/query tx))))

(defn- print-ops [title ops]
  (println (str "\n" title))
  (doseq [{:keys [disposition op detail count]} (mailchimp-sync/summarize ops)]
    (println (format "%-7s %-20s %-40s %d" (name disposition) (name op) (or detail "") count))))

(defn- print-summary [member-rows contacts mailchimp-ops stripe-ops notices]
  (println "Dry run. Nothing was changed or sent.")
  (println "Members:" (count member-rows)
           "| with a plan:" (count (filter :subscription_plan member-rows))
           "| Mailchimp contacts:" (count contacts))
  (print-ops "Mailchimp" mailchimp-ops)
  (if stripe-ops
    (print-ops "Stripe" stripe-ops)
    (println "\nStripe: skipped, set STRIPE_READ_KEY to include it"))
  (println "\nNotices that would be sent:" (count notices)))

(defn- details-line [op]
  (str "- " (:member-name op) " | " (:contact op) " | "
       (pr-str (dissoc op :disposition :op :member-name :contact))))

(defn- ops-section [ops]
  (mapcat (fn [[[disposition kind] grouped]]
            (concat [(str "\n## " (name disposition) ": " (name kind) " (" (count grouped) ")\n")]
                    (map details-line (sort-by (comp str :member-name) grouped))))
          (sort-by (fn [[[disposition kind]]] [disposition kind])
                   (group-by (juxt :disposition :op) ops))))

(defn- stripe-email-line [{:keys [member-name contact from to]}]
  (str "- " member-name " | Stripe now: " (or from "(none)") " | app billing email: " to
       " | https://dashboard.stripe.com/customers/" contact))

(defn- stripe-section [ops]
  (let [changes (filter #(= :set-stripe-email (:op %)) ops)]
    (concat [(str "# Stripe email changes to check (" (count changes) ")\n")
             "Check each customer for invoice recipients set in the Stripe dashboard. The API can't show them."
             ""]
            (map stripe-email-line (sort-by (comp str :member-name) changes))
            (ops-section (remove #(= :set-stripe-email (:op %)) ops)))))

(defn- notice-section [notices]
  (concat [(str "\n# Notice previews (" (count notices) ")")]
          (mapcat (fn [{:keys [to from subject body]}]
                    ["" (str "To: " to) (str "From: " from) (str "Subject: " subject) "" "```text" (str/trim body) "```"])
                  (sort-by :to notices))))

(defn- details [mailchimp-ops stripe-ops notices]
  (str/join
   "\n"
   (concat (when stripe-ops (stripe-section stripe-ops))
           (notice-section notices)
           ["\n# Mailchimp changes"]
           (ops-section mailchimp-ops))))

(defn -main
  "Pass a file path to also write each change and notice, which include names and addresses."
  [& [details-path]]
  (let [member-rows (members (required-env "MAILCHIMP_SYNC_JDBC_URL" "JDBC_DATABASE_URL"))
        contacts (mailchimp/contacts {:api-key (required-env "MAILCHIMP_API_KEY")
                                      :list-id (required-env "MAILCHIMP_LIST_ID")})
        mailchimp-ops (mailchimp-sync/plan member-rows contacts)
        stripe-ops (when-let [stripe-key (System/getenv "STRIPE_READ_KEY")]
                     (stripe-sync/plan member-rows (stripe/customers stripe-key)))
        notices (mailchimp-sync/notices member-rows mailchimp-ops)]
    (print-summary member-rows contacts mailchimp-ops stripe-ops notices)
    (when details-path
      (spit details-path (str (details mailchimp-ops stripe-ops notices) "\n"))
      (println "Details written to" details-path))))
