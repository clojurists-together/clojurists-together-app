(ns org.clojuriststogether.app.stripe-sync
  "Works out which Stripe customers' emails differ from the billing email in the app.

  Stripe sends invoices to the customer's email, so the app's billing email has to reach it.
  Every op is held for review, because invoice recipients set in the Stripe dashboard aren't
  visible through the API and a person has to check for them first."
  (:require [clojure.string :as str]))

(defn- clean [s]
  (when s
    (let [trimmed (str/trim (str s))]
      (when-not (str/blank? trimmed) trimmed))))

(defn billing-email
  "The app shows the login email as the billing email when none has been entered."
  [member]
  (or (clean (:invoicing_email member)) (clean (:email member))))

(defn- member-op [member kind details]
  (merge {:op kind
          :disposition :review
          :contact (:stripe_customer_id member)
          :member-id (:id member)
          :member-name (or (clean (:organization_name member)) (clean (:person_name member)))}
         details))

(defn plan [members customers]
  (let [by-id (into {} (map (juxt :id identity)) customers)]
    (vec
     (for [member members
           :when (clean (:subscription_plan member))
           :let [customer (get by-id (:stripe_customer_id member))
                 wanted (billing-email member)
                 current (clean (:email customer))]
           :when (or (nil? customer)
                     (not= (some-> wanted str/lower-case) (some-> current str/lower-case)))]
       (if customer
         (member-op member :set-stripe-email {:from current :to wanted})
         (member-op member :flag {:reason :no-stripe-customer}))))))
