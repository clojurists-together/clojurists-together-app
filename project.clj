(defproject org.clojuriststogether/app "0.1.0-SNAPSHOT"
  :description "Clojurists Together membership app"
  :url "https://app.clojuriststogether.org"
  :license {:name "EPL-2.0 OR GPL-2.0-or-later WITH Classpath-exception-2.0"
            :url "https://www.eclipse.org/legal/epl-2.0/"}
  :dependencies [[org.clojure/clojure "1.12.6"]
                 [ring/ring-jetty-adapter "1.15.6"]
                 [metosin/reitit "0.11.0"]
                 [ring/ring-defaults "0.7.1"]
                 [com.stripe/stripe-java "34.1.0"]
                 [metosin/ring-http-response "0.9.5"]
                 [hiccup "2.0.0"]
                 [integrant "1.0.1"]
                 [dev.weavejester/ragtime "0.12.1"]
                 [org.clojure/java.jdbc "0.7.12"]
                 [aero "1.1.6"]
                 [cheshire "6.2.1"]
                 [org.postgresql/postgresql "42.7.14"]
                 [honeysql "1.0.461"]
                 [io.sentry/sentry-clj "8.34.247"]
                 [clj-http "3.13.1"]
                 ;; Logging
                 [org.clojure/tools.logging "1.3.1"]
                 [org.slf4j/jcl-over-slf4j "2.0.20"]
                 [org.slf4j/jul-to-slf4j "2.0.20"]
                 [org.slf4j/log4j-over-slf4j "2.0.20"]
                 [ch.qos.logback/logback-classic "1.6.6"
                  :exclusions [org.slf4j/slf4j-api]]
                 [hikari-cp "4.1.0"]]
  :min-lein-version "2.7.1"
  :main org.clojuriststogether.app.main
  :target-path "target/%s"
  :uberjar-name "clojurists-together-app.jar"
  :aliases {"migrate"  ["run" "-m" "dev/migrate"]
            "rollback" ["run" "-m" "dev/rollback"]}
  :profiles {:uberjar {:aot :all}
             :dev     {:dependencies [[integrant/repl "0.5.1"]]
                       :resource-paths ["dev-resources"]
                       :source-paths ["dev"]}})
