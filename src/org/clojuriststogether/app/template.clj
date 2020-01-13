(ns org.clojuriststogether.app.template
  (:require [hiccup.core :as hiccup]))

(defn template [req]
  (hiccup/html
    [:head
     [:title "Clojurists Together Members"]
     [:link {:href "https://unpkg.com/tailwindcss@^1.0/dist/tailwind.min.css" :rel "stylesheet"}]]
    [:h1 "Clojurists Together Members"]))
