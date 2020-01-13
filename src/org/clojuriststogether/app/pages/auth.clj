(ns org.clojuriststogether.app.pages.auth
  (:require [org.clojuriststogether.app.template :as template]
            [ring.util.http-response :as response]))

(defn login-page [req]
  (template/template req
    [:div.bg-white.rounded-t-lg.overflow-hidden.border-t.border-l.border-r.border-gray-400.p-4.px-3.py-10.bg-gray-200.flex.justify-center
     [:div.w-full.max-w-xs
      [:form.bg-white.shadow-md.rounded.px-8.pt-6.pb-8.mb-4
       [:div.mb-4
        [:label.block.text-gray-700.text-sm.font-bold.mb-2 {:for "username"} "Username"]
        [:input#username.shadow.appearance-none.border.rounded.w-full.py-2.px-3.text-gray-700.leading-tight.focus:outline-none.focus:shadow-outline {:type "text" :placeholder "Username"}]]
       [:div.mb-6
        [:label.block.text-gray-700.text-sm.font-bold.mb-2 {:for "password"} "Password"]
        [:input#password.shadow.appearance-none.border.border-red-500.rounded.w-full.py-2.px-3.text-gray-700.mb-3.leading-tight.focus:outline-none.focus:shadow-outline {:type "password" :placeholder "******************"}]
        [:p.text-red-500.text-xs.italic "Please choose a password."]]
       [:div.flex.items-center.justify-between
        [:button.bg-blue-500.hover:bg-blue-700.text-white.font-bold.py-2.px-4.rounded.focus:outline-none.focus:shadow-outline {:type "button"} "Sign In"]
        [:a.inline-block.align-baseline.font-bold.text-sm.text-blue-500.hover:text-blue-800 {:href "#"} "Forgot Password?"]]]
      [:p.text-center.text-gray-500.text-xs "&copy;2019 Acme Corp. All rights reserved."]]]))

(defn signup-page [req]
  (template/template req
    [:div.bg-white.rounded-t-lg.overflow-hidden.border-t.border-l.border-r.border-gray-400.p-4.px-3.py-10.bg-gray-200.flex.justify-center
     [:div.w-full.max-w-xs
      [:form.bg-white.shadow-md.rounded.px-8.pt-6.pb-8.mb-4
       [:div.mb-4
        [:label.block.text-gray-700.text-sm.font-bold.mb-2 {:for "username"} "Username"]
        [:input#username.shadow.appearance-none.border.rounded.w-full.py-2.px-3.text-gray-700.leading-tight.focus:outline-none.focus:shadow-outline {:type "text" :placeholder "Username"}]]
       [:div.mb-6
        [:label.block.text-gray-700.text-sm.font-bold.mb-2 {:for "password"} "Password"]
        [:input#password.shadow.appearance-none.border.border-red-500.rounded.w-full.py-2.px-3.text-gray-700.mb-3.leading-tight.focus:outline-none.focus:shadow-outline {:type "password" :placeholder "******************"}]
        [:p.text-red-500.text-xs.italic "Please choose a password."]]
       [:div.flex.items-center.justify-between
        [:button.bg-blue-500.hover:bg-blue-700.text-white.font-bold.py-2.px-4.rounded.focus:outline-none.focus:shadow-outline {:type "button"} "Sign In"]
        [:a.inline-block.align-baseline.font-bold.text-sm.text-blue-500.hover:text-blue-800 {:href "#"} "Forgot Password?"]]]
      [:p.text-center.text-gray-500.text-xs "&copy;2019 Acme Corp. All rights reserved."]]]))

(defn auth-routes []
  [["/sign-up" {:name :sign-up
                :get  {:handler (fn [req]
                                  (-> (response/ok (template/template req))
                                      (response/content-type "text/html")))}}]
   ["/login" {:name :login
              :get  {:handler (fn [req]
                                (-> (response/ok (login-page req))
                                    (response/content-type "text/html")))}}]])