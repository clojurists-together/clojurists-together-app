(ns org.clojuriststogether.app.email
  (:refer-clojure :exclude [send])
  (:require [integrant.core :as ig]
            [clj-http.client :as client]
            [clojure.spec.alpha :as s]))

(defprotocol EmailService
  (-send [this email]))

;; Sendgrid

(defrecord SendgridEmailService [api-key]
  EmailService
  (-send [_ email]
    (client/post "https://api.sendgrid.com/v3/mail/send"
                 {:as :json
                  :content-type :json
                  :accept :json
                  :headers {"Authorization" (str "Bearer " api-key)}
                  :form-params {:personalizations [{:to [{:name (:to-name email)
                                                          :email (:to email)}]
                                                    :subject (:subject email)}]
                                :from {:email (:from email)
                                       :name (:from-name email)}
                                :content [{:type (or (:content-type email) "text/plain")
                                           :value (:body email)}]}})))


(defmethod ig/init-key ::sendgrid [_ config]
  (map->SendgridEmailService config))

(s/def ::api-key string?)

(s/def ::email-config (s/keys :req-un [::api-key]))

(defmethod ig/pre-init-spec ::sendgrid [_]
  ::email-config)

(derive ::sendgrid ::email-service)

;; Mock

(defrecord LoggingEmailService [config]
  EmailService
  (-send [_ email]
    (prn "SENT:" email)))


(defmethod ig/init-key ::logging [_ config]
  (map->LoggingEmailService config))

(derive ::logging ::email-service)

;; API

(defn send [email-service email]
  (-send email-service email))

(comment
  (send (integrant.repl.state/system ::sendgrid)
        {:to "you@email.com"
         :from "hi@clojuriststogether.org"
         :subject "Test message"
         :body "This is a test"}))
