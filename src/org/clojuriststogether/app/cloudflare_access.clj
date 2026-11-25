(ns org.clojuriststogether.app.cloudflare-access
  (:require [clojure.string :as str])
  (:import (com.nimbusds.jose JOSEException JWSAlgorithm)
           (com.nimbusds.jose.jwk.source JWKSourceBuilder)
           (com.nimbusds.jose.proc BadJOSEException JWSVerificationKeySelector)
           (com.nimbusds.jose.util DefaultResourceRetriever)
           (com.nimbusds.jwt JWTClaimsSet JWTClaimsSet$Builder)
           (com.nimbusds.jwt.proc DefaultJWTClaimsVerifier DefaultJWTProcessor)
           (java.net HttpURLConnection URI URL)
           (java.text ParseException)))

(defn- issuer-origin [issuer]
  (let [uri (try (URI. issuer) (catch Exception _ nil))]
    (when-not (and uri
                   (= "https" (.getScheme uri))
                   (some->> (.getHost uri) (re-matches #"[a-z0-9](?:[a-z0-9-]*[a-z0-9])?\.cloudflareaccess\.com"))
                   (nil? (.getUserInfo uri))
                   (= -1 (.getPort uri))
                   (nil? (.getQuery uri))
                   (nil? (.getFragment uri))
                   (#{"" "/"} (.getPath uri)))
      (throw (ex-info "Cloudflare Access issuer must be an HTTPS team.cloudflareaccess.com origin" {})))
    (str "https://" (.getHost uri))))

(defn- remote-keys [^URL url]
  (let [retriever (proxy [DefaultResourceRetriever] [2000 2000 100000]
                    (openHTTPConnection [url]
                      (doto ^HttpURLConnection (.openConnection ^URL url)
                        (.setInstanceFollowRedirects false))))]
    (-> (JWKSourceBuilder/create url retriever)
        (.cache 300000 2500)
        (.refreshAheadCache false)
        (.build))))

(defn- jwt-processor [issuer audience]
  (let [claims (-> (JWTClaimsSet$Builder.) (.issuer issuer) .build)
        verifier (doto (DefaultJWTClaimsVerifier. ^String audience claims #{"exp" "email"})
                   (.setMaxClockSkew 0))]
    (doto (DefaultJWTProcessor.)
      (.setJWSKeySelector
       (JWSVerificationKeySelector. JWSAlgorithm/RS256
                                    (remote-keys (URL. (str issuer "/cdn-cgi/access/certs")))))
      (.setJWTClaimsSetVerifier verifier))))

(defn- denial [status]
  {:status status
   :headers {"Cache-Control" "no-store"}
   :body (if (= status 503) "Admin access temporarily unavailable" "Cloudflare Access required")})

(defn- verify-token [^DefaultJWTProcessor processor token]
  (if-not (and (string? token) (not (str/blank? token)))
    403
    (try
      (let [^JWTClaimsSet claims (.process processor ^String token nil)
            email (.getStringClaim claims "email")]
        (when-not (and (string? email) (re-matches #"[^\s@]+@[^\s@]+" email))
          403))
      (catch ParseException _ 403)
      (catch BadJOSEException _ 403)
      (catch JOSEException _ 503))))

(defn middleware
  "Build once at handler initialization. Access is an additional gate; it never
  creates or changes the application's member session."
  [profile {:keys [issuer audience]}]
  (let [issuer (when-not (str/blank? issuer) (issuer-origin issuer))
        audience (when-not (str/blank? audience) audience)]
    (cond
      (and issuer audience)
      (let [processor (jwt-processor issuer audience)]
        (fn [handler]
          (fn [req]
            (if-let [status (verify-token processor (get-in req [:headers "cf-access-jwt-assertion"]))]
              (denial status)
              (handler req)))))

      (not (#{:dev :test} profile)) (fn [_handler] (constantly (denial 503)))
      (and (nil? issuer) (nil? audience)) identity
      :else (throw (ex-info "Set both Cloudflare Access issuer and audience" {})))))
