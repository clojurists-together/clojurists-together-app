(ns org.clojuriststogether.app.admin.mailchimp-apply
  "Brings Mailchimp in line with the app. Run on a schedule with no arguments.

  --send-notices  also emails members whose mailing address changed, for the first cleanup.
  --stripe        also updates Stripe customer emails to the app's billing email."
  (:require [org.clojuriststogether.app.admin.mailchimp-dry-run :as dry-run]
            [org.clojuriststogether.app.email :as email]
            [org.clojuriststogether.app.mailchimp :as mailchimp]
            [org.clojuriststogether.app.mailchimp-sync :as mailchimp-sync]
            [org.clojuriststogether.app.stripe :as stripe]
            [org.clojuriststogether.app.stripe-sync :as stripe-sync])
  (:gen-class))

(defn- op-applier [config interests]
  {:create-contact #(mailchimp/create-contact! config interests %)
   :add-tag #(mailchimp/set-tag! config (:contact %) (:tag %) true)
   :remove-tag #(mailchimp/set-tag! config (:contact %) (:tag %) false)
   :set-merge-field #(mailchimp/set-merge-field! config (:contact %) (:field %) (:to %))
   :clear-merge-field #(mailchimp/set-merge-field! config (:contact %) (:field %) "")
   :set-member-type #(mailchimp/set-member-type! config interests (:contact %) (:to %))
   :set-email #(mailchimp/set-email! config (:contact %) (:to %))})

(defn runnable-ops
  "Ops to run, in order. Changing an address comes last so a contact's other
  changes still find it at its current address."
  [ops]
  (->> ops
       (filter #(= :apply (:disposition %)))
       (sort-by #(= :set-email (:op %)))))

(defn run-ops!
  "Runs each op, carrying on past failures because the next run retries whatever is left.
  Returns the failed ops. Logs ids only, as scheduled output goes to Heroku's logs."
  [appliers ops]
  (vec
   (keep (fn [op]
           (try
             ((get appliers (:op op)) op)
             nil
             (catch Exception e
               (println "Failed" (name (:op op)) "for member" (:member-id op) (.getMessage e))
               op)))
         ops)))

(defn- send-notices! [email-service notices failed-emails]
  (doseq [notice notices
          :when (not (contains? failed-emails (:to notice)))]
    (try
      (email/send email-service notice)
      (catch Exception e
        (println "Failed to send a notice" (.getMessage e))))))

(defn -main [& args]
  (let [flags (set args)
        member-rows (dry-run/members (dry-run/required-env "MAILCHIMP_SYNC_JDBC_URL" "JDBC_DATABASE_URL"))
        config {:api-key (dry-run/required-env "MAILCHIMP_API_KEY")
                :list-id (dry-run/required-env "MAILCHIMP_LIST_ID")}
        ops (mailchimp-sync/plan member-rows (mailchimp/contacts config))]
    (when-let [reason (mailchimp-sync/unsafe-reason member-rows ops)]
      (println "Stopped, nothing was changed:" reason)
      (System/exit 2))
    (let [notices (mailchimp-sync/notices member-rows ops)
          runnable (runnable-ops ops)
          appliers (op-applier config (mailchimp/interest-ids config))
          failed (run-ops! appliers runnable)
          failed-ids (set (map :member-id failed))
          failed-emails (set (map :email (filter #(contains? failed-ids (:id %)) member-rows)))
          stripe-failed (when (flags "--stripe")
                          (let [stripe-key (dry-run/required-env "STRIPE_SECRET_KEY")
                                changes (filter #(= :set-stripe-email (:op %))
                                                (stripe-sync/plan member-rows (stripe/customers stripe-key)))]
                            (println "Stripe emails to change:" (count changes))
                            (run-ops! {:set-stripe-email #(stripe/set-customer-email! stripe-key (:contact %) (:to %))}
                                      changes)))]
      (when (flags "--send-notices")
        (send-notices! (email/map->SendgridEmailService {:api-key (dry-run/required-env "SENDGRID_API_KEY")})
                       notices
                       failed-emails)
        (println "Notices attempted:" (count (remove #(contains? failed-emails (:to %)) notices))))
      (println "Applied:" (- (count runnable) (count failed))
               "| failed:" (count failed)
               "| left for review:" (count (filter #(= :review (:disposition %)) ops)))
      (when (or (seq failed) (seq stripe-failed))
        (System/exit 1)))))
