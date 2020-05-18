(ns org.clojuriststogether.app.template
  (:require [hiccup.core :as hiccup]
            [org.clojuriststogether.app.utils :as utils]
            [ring.util.anti-forgery :as anti-forgery]))

(defn template [req & body]
  ;; TODO: hiccup2
  (hiccup/html
    [:head
     [:title "Clojurists Together Members"]
     [:link {:href "https://unpkg.com/tailwindcss@^1.0/dist/tailwind.min.css" :rel "stylesheet"}]
     [:script {:src "https://js.stripe.com/v3/"}]]
    [:body
     [:nav.bg-white.shadow {:role "navigation"}
      [:div.container.mx-auto.p-4.flex.flex-wrap.items-center.md:flex-no-wrap
       [:div.mr-4.md:mr-8
        [:a {:href (utils/route-name->path req :home) :rel "home"}
         "Clojurists Together"
         ;; Put our logo here
         #_[:svg.w-10.h-10.text-purple-600 {:width "54" :height "54" :viewBox "0 0 54 54" :xmlns "http://www.w3.org/2000/svg"}
            [:title "TailwindCSS"]
            [:path {:fill "currentColor" :d "M13.5 22.1c1.8-7.2 6.3-10.8 13.5-10.8 10.8 0 12.15 8.1 17.55 9.45 3.6.9 6.75-.45 9.45-4.05-1.8 7.2-6.3 10.8-13.5 10.8-10.8 0-12.15-8.1-17.55-9.45-3.6-.9-6.75.45-9.45 4.05zM0 38.3c1.8-7.2 6.3-10.8 13.5-10.8 10.8 0 12.15 8.1 17.55 9.45 3.6.9 6.75-.45 9.45-4.05-1.8 7.2-6.3 10.8-13.5 10.8-10.8 0-12.15-8.1-17.55-9.45-3.6-.9-6.75.45-9.45 4.05z"}]]]]
       [:div.ml-auto.md:hidden
        [:button.flex.items-center.px-3.py-2.border.rounded {:type "button"}
         [:svg.h-3.w-3 {:viewBox "0 0 20 20" :xmlns "http://www.w3.org/2000/svg"}
          [:title "Menu"]
          [:path {:d "M0 3h20v2H0V3zm0 6h20v2H0V9zm0 6h20v2H0v-2z"}]]]]
       [:div.w-full.md:w-auto.md:flex-grow.md:flex.md:items-center
        #_[:ul.flex.flex-col.mt-4.-mx-4.pt-4.border-t.md:flex-row.md:items-center.md:mx-0.md:mt-0.md:pt-0.md:mr-4.lg:mr-8.md:border-0
           [:li
            [:a.block.px-4.py-1.md:p-2.lg:px-4 {:href "#" :title "Link"} "Link"]]
           [:li
            [:a.block.px-4.py-1.md:p-2.lg:px-4.text-purple-600 {:href "#" :title "Active Link"} "Active Link"]]
           [:li
            [:a.block.px-4.py-1.md:p-2.lg:px-4 {:href "#" :title "Link"} "Link"]]]
        [:ul.flex.flex-col.mt-4.-mx-4.pt-4.border-t.md:flex-row.md:items-center.md:mx-0.md:ml-auto.md:mt-0.md:pt-0.md:border-0
         (if (get-in req [:session :member-id])
           [:li
            [:form {:method "POST" :action (utils/route-name->path req :logout)
                    :style "margin-block-end: 0;"}
             (anti-forgery/anti-forgery-field)
             [:button.block.px-4.py-1.md:p-2.lg:px-4 "Logout"]]]
           [:li
            [:a.block.px-4.py-1.md:p-2.lg:px-4 {:href (utils/login-path req) :title "Login"} "Login"]])
         #_[:li
            [:a.block.px-4.py-1.md:p-2.lg:px-4.text-purple-600 {:href (utils/route-name->path req :sign-up)} "Sign Up"]]]]]]
     [:div.bg-white.rounded-t-lg.overflow-hidden.border-t.border-l.border-r.border-gray-400.p-4.px-3.py-10.bg-gray-200.flex.justify-center
      [:div.w-full.max-w-xs
       body]]
     ]))
