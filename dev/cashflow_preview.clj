(ns cashflow-preview
  (:require [clojure.core.server :as repl]
            [org.clojuriststogether.app.pages.cashflow :as cashflow]
            [ring.adapter.jetty :as jetty]
            [ring.middleware.defaults :as defaults]
            [ring.middleware.session.memory :as memory]
            [clojure.java.jdbc :as jdbc]
            [clojure.string :as str]))
(import '(com.zaxxer.hikari HikariConfig HikariDataSource)
        '(com.stripe Stripe)
        '(java.net URI)
        '(java.security SecureRandom MessageDigest)
        '(java.util Base64))
(defn required-env [name]
  (or (not-empty (System/getenv name)) (throw (ex-info (str "Missing environment variable " name) {}))))
(def raw-url (required-env "JDBC_DATABASE_URL"))
(def db-config
  (if (str/starts-with? raw-url "jdbc:")
    {:url raw-url}
    (let [uri (URI. raw-url)
          [username password] (str/split (.getUserInfo uri) #":" 2)]
      {:url (str "jdbc:postgresql://" (.getHost uri) ":" (if (pos? (.getPort uri)) (.getPort uri) 5432)
                 (.getPath uri) (when (.getQuery uri) (str "?" (.getQuery uri))))
       :username username :password password})))
(def hikari (doto (HikariConfig.)
              (.setJdbcUrl (:url db-config))
              (.setReadOnly true)
              (.setConnectionInitSql "SET default_transaction_read_only = on")
              (.setMaximumPoolSize 2)
              (.setConnectionTimeout 15000)))
(when (:username db-config) (.setUsername hikari (:username db-config)))
(when (:password db-config) (.setPassword hikari (:password db-config)))
(def datasource (HikariDataSource. hikari))
(def db {:datasource datasource})
(assert (= "on" (:transaction_read_only (first (jdbc/query db ["SHOW transaction_read_only"])))))
(set! Stripe/apiKey (required-env "STRIPE_SECRET_KEY"))
(def secret-bytes (byte-array 32))
(.nextBytes (SecureRandom.) secret-bytes)
(def login-token (.encodeToString (.withoutPadding (Base64/getUrlEncoder)) secret-bytes))
(defn token-matches? [candidate]
  (and (string? candidate)
       (MessageDigest/isEqual (.getBytes login-token "UTF-8") (.getBytes candidate "UTF-8"))))
(defn reload! []
  (doseq [namespace '[org.clojuriststogether.app.cashflow
                      org.clojuriststogether.app.stripe
                      org.clojuriststogether.app.cashflow-xlsx
                      org.clojuriststogether.app.pages.cashflow]]
    (require namespace :reload))
  :reloaded)

(def handler
  (defaults/wrap-defaults
   (fn [req]
     (assoc-in
      (cond
        (not= :get (:request-method req)) {:status 405 :body "Read-only preview"}
        (and (= "/preview-login" (:uri req)) (token-matches? (get-in req [:query-params "token"])))
        {:status 302 :headers {"Location" "/admin/cashflow"} :session {:preview-admin true} :body ""}
        (not (get-in req [:session :preview-admin])) {:status 403 :body "Open the temporary preview sign-in link."}
        (= "/admin/cashflow" (:uri req)) ((cashflow/handler db false) req)
        (= "/admin/cashflow.xlsx" (:uri req)) ((cashflow/handler db true) req)
        :else {:status 302 :headers {"Location" "/admin/cashflow"} :body ""})
      [:headers "Cache-Control"] "no-store"))
   {:responses {:content-types true :default-charset "utf-8"}
    :static {:resources "public"}
    :params {:urlencoded true :keywordize false}
    :cookies true
    :session {:store (memory/memory-store) :cookie-name "cashflow-preview"
              :cookie-attrs {:http-only true :same-site :strict}}
    :security {:frame-options :deny :content-type-options :nosniff}}))
(def server (jetty/run-jetty handler {:host "127.0.0.1" :port 3000 :join? false}))
(java.nio.file.Files/createDirectories (java.nio.file.Paths/get "target/cashflow-preview" (make-array String 0)) (make-array java.nio.file.attribute.FileAttribute 0))
(spit "target/cashflow-preview/login-url" (str "http://127.0.0.1:3000/preview-login?token=" login-token))
(println "Read-only production preview listening on 127.0.0.1:3000; sign-in URL saved locally.")

(defn -main [& _]
  (repl/start-server {:name "cashflow-preview" :address "127.0.0.1" :port 5555
                      :accept 'clojure.core.server/repl :server-daemon true})
  (println "Clojure socket REPL on 127.0.0.1:5555. Evaluate (cashflow-preview/reload!) after code edits."))
