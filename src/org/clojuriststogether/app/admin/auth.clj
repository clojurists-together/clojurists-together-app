(ns org.clojuriststogether.app.admin.auth
  (:require [org.clojuriststogether.app.pages.auth :as auth]
            [org.clojuriststogether.app.sessions :as sessions]))

(defn wrap-admin [handler db]
  (fn [req]
    ;; Reload the role so revoking access also affects existing sessions.
    (let [member (when-let [id (sessions/member-id req)] (auth/get-member db id))]
      (assoc-in
       (cond
         (nil? member) {:status 302 :headers {"Location" "/login"} :body ""}
         (not (true? (:is_admin member))) {:status 403 :body "Admin access required"}
         :else (handler (assoc req :admin-member member)))
       [:headers "Cache-Control"] "no-store"))))
