(ns org.clojuriststogether.app.admin.mailchimp-apply-test
  (:require [clojure.test :refer [deftest is testing]]
            [org.clojuriststogether.app.admin.mailchimp-apply :as apply]))

(deftest only-unattended-ops-run-and-address-changes-go-last
  (let [ops [{:op :set-email :disposition :apply}
             {:op :add-tag :disposition :apply}
             {:op :create-contact :disposition :review}
             {:op :remove-tag :disposition :apply}]]
    (is (= [:add-tag :remove-tag :set-email] (map :op (apply/runnable-ops ops))))))

(deftest one-failure-does-not-stop-the-rest
  (let [ran (atom [])
        appliers {:add-tag #(swap! ran conj (:member-id %))
                  :set-email (fn [_] (throw (ex-info "rejected" {})))}
        failed (with-out-str
                 (is (= [{:op :set-email :member-id 2}]
                        (apply/run-ops! appliers [{:op :add-tag :member-id 1}
                                                  {:op :set-email :member-id 2}
                                                  {:op :add-tag :member-id 3}]))))]
    (is (= [1 3] @ran))
    (testing "the log holds ids, not addresses"
      (is (re-find #"Failed set-email for member 2" failed)))))
