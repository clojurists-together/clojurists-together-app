(ns org.clojuriststogether.app.subscription-events
  "Pure interpretation of subscription snapshots. Never expose arbitrary webhook values.")

(def routine-fields
  "Housekeeping and private fields intentionally omitted from billing notifications."
  #{:metadata :latest_invoice :current_period_start :current_period_end :updated
    :default_payment_method :default_source :payment_settings :pending_setup_intent
    :cancellation_details :canceled_at :ended_at :created :livemode :object :id
    :application})

(defn restore-previous
  "Stripe previous maps can be partial; arrays are complete snapshots."
  [current previous]
  (if (and (map? current) (map? previous))
    (merge-with restore-previous current previous)
    previous))

(defn- object-id [value]
  (if (map? value) (:id value) value))

(defn- safe-id [value]
  (let [id (object-id value)]
    (when (and (string? id) (re-matches #"(?:[A-Za-z][A-Za-z0-9]*|sub_sched)_[A-Za-z0-9]+" id)) id)))

(defn- references [value]
  (if (sequential? value) (sort-by str (map object-id value)) (object-id value)))

(defn- price-value [price]
  (if (map? price)
    (cond-> (select-keys price [:id :currency :unit_amount :unit_amount_decimal :amount
                                :interval :interval_count :billing_scheme :tiers_mode :transform_quantity])
      (:product price) (assoc :product (object-id (:product price)))
      (:recurring price) (assoc :recurring (select-keys (:recurring price) [:interval :interval_count :usage_type :meter])))
    price))

(defn- item-value [item]
  (cond-> (-> (select-keys item [:trial_end])
              (assoc :price (let [price (or (:price item) (:plan item))]
                              (or (object-id price) (price-value price)))
                     :quantity (or (:quantity item) 1)))
    (:billing_thresholds item) (assoc :billing_thresholds
                                      (into {} (filter (fn [[_ v]] (or (number? v) (boolean? v)))
                                                       (select-keys (:billing_thresholds item) [:amount_gte :usage_gte :reset_billing_cycle_anchor]))))
    (:current_trial item) (assoc :current_trial (select-keys (:current_trial item) [:start_date :end_date :trial_offer]))
    (seq (:discounts item)) (assoc :discounts (references (:discounts item)))
    (seq (:tax_rates item)) (assoc :tax_rates (references (:tax_rates item)))))

(defn- display-item [item]
  (let [price (or (:price item) (:plan item))]
    (assoc (cond-> (item-value item)
             (:current_trial item) (update :current_trial #(cond-> (select-keys % [:start_date :end_date])
                                                             (:trial_offer %) (assoc :trial_offer (or (safe-id (:trial_offer %)) "Details withheld"))))
             (seq (:discounts item)) (assoc :discounts (mapv #(or (safe-id %) "Details withheld") (:discounts item)))
             (seq (:tax_rates item)) (assoc :tax_rates (mapv #(or (safe-id %) "Details withheld") (:tax_rates item)))) :price
           (if (map? price)
             (merge (price-value price) (select-keys price [:nickname :lookup_key])
                    (when (map? (:product price)) {:product (select-keys (:product price) [:id :name])}))
             price))))

(defn- item-list [subscription]
  (or (seq (get-in subscription [:items :data]))
      (when (:plan subscription) [{:plan (:plan subscription) :quantity (:quantity subscription)}])
      []))

(defn- same-items? [before after ignored-fields]
  (let [values (fn [subscription]
                 (frequencies (map #(apply dissoc (item-value %) ignored-fields) (item-list subscription))))]
    (= (values before) (values after))))

(def field-specs
  [[:cancel_at_period_end "Cancel at period end" :boolean]
   [:cancel_at "Cancellation date" :date]
   [:status "Status" :status]
   [:quantity "Quantity" :number]
   [:discount "Discount" :references]
   [:discounts "Discounts" :references]
   [:default_tax_rates "Tax rates" :references]
   [:automatic_tax "Automatic tax" :settings]
   [:pause_collection "Payment collection" :settings]
   [:trial_start "Trial start" :date]
   [:trial_end "Trial end" :date]
   [:trial_settings "Trial settings" :settings]
   [:billing_mode "Billing mode" :settings]
   [:collection_method "Collection method" :enum]
   [:billing_cycle_anchor "Billing cycle anchor" :date]
   [:billing_thresholds "Billing thresholds" :settings]
   [:days_until_due "Days until due" :number]
   [:currency "Currency" :enum]
   [:pending_update "Pending update" :withheld]
   [:schedule "Subscription schedule" :references]
   [:pending_invoice_item_interval "Pending invoice interval" :settings]
   [:invoice_settings "Invoice settings" :withheld]])

(def safe-enums
  #{"active" "past_due" "trialing" "paused" "unpaid" "canceled" "incomplete" "incomplete_expired"
    "classic" "flexible" "charge_automatically" "send_invoice" "void" "keep_as_draft" "mark_uncollectible"
    "cancel" "pause" "create_invoice" "month" "year" "week" "day"})

(defn- safe-setting [value]
  (cond
    (nil? value) nil
    (boolean? value) value
    (number? value) value
    (safe-enums value) value
    :else "Details withheld"))

(def setting-keys
  #{:enabled :behavior :resumes_at :type :amount_gte :reset_billing_cycle_anchor
    :end_behavior :missing_payment_method :interval :interval_count})

(defn- settings-value [value]
  (if (map? value)
    (into (sorted-map)
          (for [[k v] value :when (setting-keys k)]
            [k (if (map? v) (settings-value v) (safe-setting v))]))
    (safe-setting value)))

(defn- display-value [format value]
  (case format
    :withheld "Details withheld"
    :references (if (sequential? value) (mapv #(or (safe-id %) "Details withheld") (take 8 value))
                    (when value (or (safe-id value) "Details withheld")))
    :settings (settings-value value)
    :enum (if (and (string? value) (or (safe-enums value) (re-matches #"[a-z]{3}" value))) value "Details withheld")
    :status (if (safe-enums value) value "Unknown status")
    (:date :number) (when (number? value) value)
    :boolean (when (boolean? value) value)))

(defn- cancellation? [s]
  (or (true? (:cancel_at_period_end s)) (some? (:cancel_at s))))

(def status-titles
  {"past_due" "Payment failed — subscription past due"
   "trialing" "Subscription trial started"
   "paused" "Subscription paused"
   "unpaid" "Subscription unpaid"
   "canceled" "Subscription ended"
   "incomplete" "Subscription awaiting payment"
   "incomplete_expired" "Subscription initial payment expired"})

(defn update-details [{:keys [type data]}]
  (when (= type "customer.subscription.updated")
    (let [current (:object data)
          previous (:previous_attributes data)
          before (restore-previous current previous)
          before (if (and (contains? previous :plan) (not (contains? previous :items))
                          (= 1 (count (get-in before [:items :data]))))
                   (assoc-in before [:items :data]
                             (if-let [plan (:plan before)]
                               [(-> (first (get-in before [:items :data]))
                                    (dissoc :plan)
                                    (assoc :price plan :quantity (or (:quantity before)
                                                                     (:quantity (first (get-in before [:items :data]))) 1)))]
                               []))
                   before)
          changed? #(and (contains? previous %)
                         (not= (if (#{:discount :discounts :default_tax_rates :schedule} %) (references (get before %)) (get before %))
                               (if (#{:discount :discounts :default_tax_rates :schedule} %) (references (get current %)) (get current %))))
          items-changed? (and (some #(contains? previous %) [:items :plan]) (not (same-items? before current [])))
          item-trial-only? (and items-changed? (same-items? before current [:current_trial :trial_end]))
          known (into routine-fields (concat [:items :plan] (map first field-specs)))
          changes (vec (concat
                        (when items-changed?
                          [{:field :items :label "Membership" :before (mapv display-item (item-list before))
                            :after (mapv display-item (item-list current)) :format :items}])
                        (for [[field label format] field-specs :when (changed? field)]
                          {:field field :label label :before (display-value format (get before field))
                           :after (display-value format (get current field)) :format format})
                        (for [field (sort (remove known (keys previous))) :when (changed? field)]
                          {:field field :label (if (re-matches #"[a-z_]{1,64}" (name field)) (name field) "Other field")
                           :before "Details withheld" :after "Details withheld" :format :withheld})))
          fields (set (map :field changes))
          cancel-changed? (some fields [:cancel_at :cancel_at_period_end])
          kind (cond cancel-changed? (if (cancellation? current) :cancellation-scheduled :cancellation-reversed)
                     (seq changes) :updated)
          title (cond
                  cancel-changed? (if (cancellation? current)
                                    (if (cancellation? before) "Subscription cancellation date changed" "Subscription cancellation scheduled")
                                    "Subscription cancellation reversed")
                  (fields :status) (if (= "active" (:status current))
                                     (case (:status before)
                                       "past_due" "Payment recovered — subscription active"
                                       "trialing" "Trial ended — subscription active"
                                       "paused" "Subscription resumed"
                                       "Subscription activated")
                                     (get status-titles (:status current) "Subscription status changed"))
                  (fields :pause_collection) (cond
                                               (nil? (:pause_collection current)) "Payment collection resumed"
                                               (:pause_collection before) "Payment collection settings changed"
                                               :else "Payment collection paused")
                  (and item-trial-only? (not (fields :quantity))) "Subscription item trial changed"
                  (or items-changed? (fields :quantity)) "Subscription membership changed"
                  (some fields [:discount :discounts]) "Subscription discount changed"
                  (some fields [:default_tax_rates :automatic_tax]) "Subscription tax settings changed"
                  (some fields [:trial_start :trial_end :trial_settings]) "Subscription trial changed"
                  (some fields [:billing_mode :collection_method :billing_cycle_anchor :billing_thresholds :days_until_due
                                :currency :pending_invoice_item_interval :invoice_settings]) "Subscription billing settings changed"
                  (fields :schedule) "Subscription schedule changed"
                  (fields :pending_update) "Subscription pending update changed"
                  :else "Subscription updated")]
      (when kind
        {:kind kind :title title
         :color (cond (= kind :cancellation-scheduled) "#E59D23"
                      (= kind :cancellation-reversed) "#2E9E5B"
                      (and (fields :status) (#{"past_due" "unpaid" "canceled" "incomplete_expired"} (:status current))) "#D14343"
                      :else "#3B82F6")
         :changes changes}))))
