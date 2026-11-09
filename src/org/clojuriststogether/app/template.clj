(ns org.clojuriststogether.app.template
  (:require [hiccup.page :as page]
            [org.clojuriststogether.app.utils :as utils]
            [ring.util.anti-forgery :as anti-forgery]))

(def nav-link ["block" "py-1" "text-gray-700" "hover:text-blue-700"])

;; Default styles for the bare [:h1]/[:p] tags some pages render without classes.
;; Element selectors lose to Tailwind utility classes, so pages can still override them.
(def base-styles
  "main h1 { font-size: 1.25rem; font-weight: 600; margin-bottom: 0.5rem; }
   main p { margin-bottom: 0.25rem; }")

(defn template [req & body]
  ;; TODO: hiccup2
  (page/html5 {:lang "en"}
              [:head
               [:meta {:charset "utf-8"}]
               [:meta {:name "viewport" :content "width=device-width, initial-scale=1"}]
               [:title "Clojurists Together Members"]
               [:link {:href "https://unpkg.com/tailwindcss@^1.0/dist/tailwind.min.css" :rel "stylesheet"}]
               [:style base-styles]]
              [:body.flex.flex-col.min-h-screen.bg-gray-100.text-gray-800.antialiased
               [:nav.bg-white.border-b.border-gray-200 {:role "navigation"}
                [:div.container.mx-auto.px-4.py-3.flex.flex-wrap.items-center.justify-between
                 [:a.font-semibold.text-lg.text-gray-900 {:href (utils/route-name->path req :home) :rel "home"}
                  "Clojurists Together"
                  ;; Put our logo here
                  #_[:svg.w-10.h-10.text-purple-600 {:width "54" :height "54" :viewBox "0 0 54 54" :xmlns "http://www.w3.org/2000/svg"}
                     [:title "TailwindCSS"]
                     [:path {:fill "currentColor" :d "M13.5 22.1c1.8-7.2 6.3-10.8 13.5-10.8 10.8 0 12.15 8.1 17.55 9.45 3.6.9 6.75-.45 9.45-4.05-1.8 7.2-6.3 10.8-13.5 10.8-10.8 0-12.15-8.1-17.55-9.45-3.6-.9-6.75.45-9.45 4.05zM0 38.3c1.8-7.2 6.3-10.8 13.5-10.8 10.8 0 12.15 8.1 17.55 9.45 3.6.9 6.75-.45 9.45-4.05-1.8 7.2-6.3 10.8-13.5 10.8-10.8 0-12.15-8.1-17.55-9.45-3.6-.9-6.75.45-9.45 4.05z"}]]]
                 [:ul.flex.items-center.space-x-6.text-sm
                  [:li
                   [:a {:class nav-link :href "https://clojuriststogether.org" :title "clojuriststogether.org"} "clojuriststogether.org"]]
                  (if (get-in req [:session :member-id])
                    [:li
                     [:form {:method "POST" :action (utils/route-name->path req :logout)
                             :style "margin-block-end: 0;"}
                      (anti-forgery/anti-forgery-field)
                      [:button {:class nav-link} "Logout"]]]
                    [:li
                     [:a {:class nav-link :href (utils/login-path req) :title "Login"} "Login"]])
                  #_[:li
                     [:a.block.px-4.py-1.md:p-2.lg:px-4.text-purple-600 {:href (utils/route-name->path req :sign-up)} "Sign Up"]]]]]
               [:main.flex-grow.flex.justify-center.px-4.py-10
                [:div.w-full.max-w-md
                 body]]
               [:footer.bg-white.border-t.border-gray-200
                [:div.container.mx-auto.px-4.py-4.flex.flex-wrap.justify-between.text-sm.text-gray-600
                 [:span "© Clojurists Together Foundation"]
                 [:a.hover:text-blue-700 {:href "mailto:hi@clojuriststogether.org"} "hi@clojuriststogether.org"]]]]))
