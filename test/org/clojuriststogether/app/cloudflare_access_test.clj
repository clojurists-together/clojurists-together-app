(ns org.clojuriststogether.app.cloudflare-access-test
  (:require [clojure.test :refer [deftest is testing]]
            [integrant.core :as ig]
            [org.clojuriststogether.app.cloudflare-access :as access]
            [org.clojuriststogether.app.middleware :as middleware]
            [org.clojuriststogether.app.pages.auth :as auth]
            [org.clojuriststogether.app.pages.cashflow :as cashflow]
            [org.clojuriststogether.app.routes]
            [ring.middleware.session.memory :as memory]
            [sentry-clj.core :as sentry])
  (:import (com.nimbusds.jose JWSAlgorithm JWSHeader$Builder)
           (com.nimbusds.jose.crypto RSASSASigner)
           (com.nimbusds.jose.jwk JWKSet RSAKey RSAKey$Builder)
           (com.nimbusds.jose.jwk.gen RSAKeyGenerator)
           (com.nimbusds.jwt JWTClaimsSet$Builder PlainJWT SignedJWT)
           (com.sun.net.httpserver HttpHandler HttpServer)
           (java.net InetSocketAddress URL)
           (java.util Date)))

(def issuer "https://test-team.cloudflareaccess.com")
(def config {:issuer issuer :audience "cashflow-audience"})
(def rsa-keys (delay (mapv #(-> (RSAKeyGenerator. 2048) (.keyID %) .generate) ["first" "second"])))

(defn claims [overrides]
  (.build (reduce-kv (fn [builder k v] (.claim builder k v))
                     (JWTClaimsSet$Builder.)
                     (merge {"iss" issuer "aud" [(:audience config)]
                             "exp" (Date. (+ (System/currentTimeMillis) 60000))
                             "email" "admin@example.org"}
                            overrides))))

(defn token
  ([] (token (first @rsa-keys) {} JWSAlgorithm/RS256))
  ([key overrides] (token key overrides JWSAlgorithm/RS256))
  ([^RSAKey key overrides algorithm]
   (let [jwt (SignedJWT. (-> (JWSHeader$Builder. algorithm) (.keyID (.getKeyID key)) .build)
                         (claims overrides))]
     (.sign jwt (RSASSASigner. key))
     (.serialize jwt))))

(defn request [token]
  {:headers (cond-> {} token (assoc "cf-access-jwt-assertion" token))})

(defn with-jwks [f]
  (let [state (atom {:key (first @rsa-keys) :status 200})
        calls (atom [])
        configured-urls (atom [])
        server (HttpServer/create (InetSocketAddress. "127.0.0.1" 0) 0)
        remote-keys @#'access/remote-keys]
    (.createContext server "/"
                    (reify HttpHandler
                      (handle [_ exchange]
                        (swap! calls conj (str (.getRequestURI exchange)))
                        (when-let [location (:redirect @state)]
                          (.add (.getResponseHeaders exchange) "Location" location))
                        (let [body (.getBytes (.toString (JWKSet. (.toPublicJWK ^RSAKey (:key @state)))) "UTF-8")]
                          (.sendResponseHeaders exchange (:status @state) (alength body))
                          (with-open [out (.getResponseBody exchange)] (.write out body))))))
    (.start server)
    (try
      (with-redefs-fn {#'access/remote-keys
                       (fn [url]
                         (swap! configured-urls conj (str url))
                         (remote-keys (URL. (str "http://127.0.0.1:" (.getPort (.getAddress server))
                                                 "/cdn-cgi/access/certs"))))}
        #(f {:state state :calls calls :configured-urls configured-urls}))
      (finally (.stop server 0)))))

(deftest signed-tokens-and-claim-validation
  (with-jwks
    (fn [{:keys [calls configured-urls]}]
      (let [handler ((access/middleware :test config) (constantly {:status 200}))
            valid (token)]
        (is (= 200 (:status (handler (request valid)))))
        (is (= 200 (:status (handler (request valid)))))
        (is (= ["/cdn-cgi/access/certs"] @calls) "a cached key needs no HTTP call")
        (is (= [(str issuer "/cdn-cgi/access/certs")] @configured-urls))
        (doseq [[description jwt]
                [["missing" nil]
                 ["blank" " "]
                 ["malformed" "not-a-jwt"]
                 ["unsigned" (.serialize (PlainJWT. (claims {})))]
                 ["wrong signature" (token (-> (RSAKey$Builder. ^RSAKey (second @rsa-keys)) (.keyID "first") .build) {})]
                 ["wrong algorithm" (token (first @rsa-keys) {} JWSAlgorithm/RS512)]
                 ["wrong issuer" (token (first @rsa-keys) {"iss" "https://other.cloudflareaccess.com"})]
                 ["wrong audience" (token (first @rsa-keys) {"aud" ["other"]})]
                 ["missing audience" (token (first @rsa-keys) {"aud" nil})]
                 ["expired" (token (first @rsa-keys) {"exp" (Date. (- (System/currentTimeMillis) 1000))})]
                 ["missing expiry" (token (first @rsa-keys) {"exp" nil})]
                 ["invalid expiry" (token (first @rsa-keys) {"exp" "tomorrow"})]
                 ["not yet valid" (token (first @rsa-keys) {"nbf" (Date. (+ (System/currentTimeMillis) 60000))})]
                 ["no human email" (token (first @rsa-keys) {"email" nil})]
                 ["invalid email type" (token (first @rsa-keys) {"email" 123})]
                 ["blank email" (token (first @rsa-keys) {"email" " "})]
                 ["unknown key" (token (second @rsa-keys) {})]]]
          (testing description
            (let [result (handler (request jwt))]
              (is (= 403 (:status result)))
              (is (= "no-store" (get-in result [:headers "Cache-Control"]))))))))))

(deftest rotated-keys-and-jwks-outages
  (with-jwks
    (fn [{:keys [calls state]}]
      (let [handler ((access/middleware :test config) (constantly {:status 200}))]
        (is (= 200 (:status (handler (request (token))))))
        (swap! state assoc :key (second @rsa-keys))
        (is (= 200 (:status (handler (request (token (second @rsa-keys) {}))))))
        (is (= 2 (count @calls)))
        (is (= 200 (:status (handler (request (token (second @rsa-keys) {}))))))
        (is (= 2 (count @calls))))))
  (with-jwks
    (fn [{:keys [state]}]
      (swap! state assoc :status 503)
      (let [handler ((access/middleware :test config) (constantly {:status 200}))]
        (dotimes [_ 3]
          (let [result (handler (request (token)))]
            (is (= 503 (:status result)))
            (is (= "no-store" (get-in result [:headers "Cache-Control"])))))))))

(deftest unknown-keys-have-a-bounded-refresh-budget
  (with-jwks
    (fn [{:keys [calls]}]
      (let [handler ((access/middleware :test config) (constantly {:status 200}))
            unknown (token (second @rsa-keys) {})]
        (is (= 200 (:status (handler (request (token))))))
        (is (= 403 (:status (handler (request unknown)))))
        (is (= 503 (:status (handler (request unknown)))))
        (is (= 2 (count @calls)))
        (is (= 200 (:status (handler (request (token))))))))))

(deftest trusted-key-url-and-normalized-issuer
  (with-jwks
    (fn [_]
      (let [handler ((access/middleware :dev (update config :issuer str "/"))
                     (constantly {:status 200}))]
        (is (= 200 (:status (handler (request (token (first @rsa-keys)
                                                     {"aud" ["other" (:audience config)]})))))))))
  (with-jwks
    (fn [{:keys [state calls]}]
      (swap! state assoc :status 302 :redirect "/unexpected")
      (let [handler ((access/middleware :test config) (constantly {:status 200}))]
        (is (= 503 (:status (handler (request (token))))))
        (is (= ["/cdn-cgi/access/certs"] @calls) "JWKS redirects are not followed")))))

(deftest configuration-fails-closed
  (doseq [profile [:prod nil :unknown]
          cfg [nil {} {:issuer issuer} {:audience "aud"}]]
    (is (= 503 (:status (((access/middleware profile cfg) (constantly {:status 200})) {})))))
  (doseq [profile [:dev :test]]
    (is (= 200 (:status (((access/middleware profile nil) (constantly {:status 200})) {})))))
  (doseq [issuer ["http://test.cloudflareaccess.com" "https://evil.example"
                  "https://test.cloudflareaccess.com.evil.example" "https://cloudflareaccess.com"
                  "https://test.cloudflareaccess.com:443" "https://user@test.cloudflareaccess.com"
                  "https://test.cloudflareaccess.com/path" "https://test.cloudflareaccess.com?query=1"
                  "https://test.cloudflareaccess.com#fragment" "not a URL"]]
    (is (thrown? clojure.lang.ExceptionInfo (access/middleware :prod (assoc config :issuer issuer)))))
  (is (thrown? clojure.lang.ExceptionInfo (access/middleware :dev {:issuer issuer}))))

(defn app-request [path headers]
  {:request-method :get :uri path :scheme :https :server-name "localhost"
   :server-port 443 :headers headers})

(deftest both-routes-still-require-member-session-and-current-admin-role
  (with-jwks
    (fn [_]
      (let [role (atom true)
            runs (atom 0)
            headers {"cf-access-jwt-assertion" (token) "cookie" "ring-session=session"}]
        (with-redefs [auth/get-member (fn [& _] {:id 1 :is_admin @role})
                      cashflow/handler (fn [_ _] (fn [_] (swap! runs inc) {:status 200 :body "report"}))]
          (let [handler (ig/init-key :app/handler {:profile :test :db {} :cloudflare-access config
                                                   :store (memory/memory-store (atom {"session" {:member-id 1}}))})]
            (doseq [path ["/admin/cashflow" "/admin/cashflow.xlsx"]]
              (let [respond (fn [headers] (handler (app-request path headers)))]
                (reset! role true)
                (is (= 403 (:status (respond {"cookie" "ring-session=session"
                                              "cf-access-authenticated-user-email" "admin@example.org"}))))
                (is (= 403 (:status (respond (assoc headers "cf-access-jwt-assertion" "forged")))))
                (let [result (respond (dissoc headers "cookie"))]
                  (is (= 302 (:status result)))
                  (is (re-find #"/login$" (get-in result [:headers "Location"]))))
                (is (= 200 (:status (respond headers))))
                (reset! role false)
                (let [result (respond headers)]
                  (is (= 403 (:status result)))
                  (is (= "no-store" (get-in result [:headers "Cache-Control"]))))))
            (is (= 2 @runs))))))))

(deftest unconfigured-production-admin-does-not-disable-public-routes
  (let [handler (ig/init-key :app/handler {:profile :prod :store (memory/memory-store)})]
    (is (= 302 (:status (handler (app-request "/" {})))))
    (doseq [path ["/admin/cashflow" "/admin/cashflow.xlsx"]]
      (let [result (handler (app-request path {}))]
        (is (= 503 (:status result)))
        (is (= "no-store" (get-in result [:headers "Cache-Control"])))))))

(deftest access-token-is-not-sent-to-error-reporting
  (let [event (atom nil)]
    (with-redefs [sentry/send-event #(reset! event %)]
      (middleware/report-to-sentry (Exception. "report failed")
                                   (assoc (app-request "/admin/cashflow" {"cf-access-jwt-assertion" "private-token"
                                                                          "cookie" "CF_Authorization=private-token"
                                                                          "authorization" "Bearer private-token"
                                                                          "accept" "text/html"})
                                          :remote-addr "127.0.0.1")))
    (is (= {"accept" "text/html"} (get-in @event [:request :headers])))))
