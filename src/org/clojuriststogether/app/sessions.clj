(ns org.clojuriststogether.app.sessions
  (:require [integrant.core :as ig]
            [ring.util.codec :as codec]
            [ring.middleware.session.memory :as memory-session]
            [ring.middleware.session.cookie :as cookie-session]))

(defmethod ig/init-key :app/memory-session-store [_ _]
  (memory-session/memory-store))

(defmethod ig/suspend-key! :app/memory-session-store [_ _])

(defmethod ig/resume-key :app/memory-session-store [k v old-val old-impl]
  old-impl)

(defmethod ig/init-key :app/cookie-session-store [_ config]
  (let [key-bytes (some-> (:key config) (codec/base64-decode))]
    (cookie-session/cookie-store {:key key-bytes})))

(defmethod ig/suspend-key! :app/cookie-session-store [_ _])

(defmethod ig/resume-key :app/cookie-session-store [k opts old-impl old-opts]
  (if (= opts old-opts)
    old-impl
    (do
      (ig/halt-key! k old-impl)
      (ig/init-key k opts))))

(derive :app/memory-session-store :app/session-store)
(derive :app/cookie-session-store :app/session-store)
