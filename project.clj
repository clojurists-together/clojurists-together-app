(defproject org.clojuriststogether/app "0.1.0-SNAPSHOT"
  :description "Clojurists Together membership app"
  :url "https://app.clojuriststogether.org"
  :license {:name "EPL-2.0 OR GPL-2.0-or-later WITH Classpath-exception-2.0"
            :url "https://www.eclipse.org/legal/epl-2.0/"}
  :dependencies [[org.clojure/clojure "1.10.1"]
                 [ring/ring-jetty-adapter "1.7.1"]
                 [metosin/reitit "0.4.2"]
                 [ring/ring-defaults "0.3.2"]
                 [com.stripe/stripe-java "19.9.0"]
                 [metosin/ring-http-response "0.9.1"]
                 [hiccup "2.0.0-alpha1"]
                 [integrant "0.7.0"]
                 [ragtime "0.8.0"]
                 [aero "1.1.4"]
                 [cheshire "5.10.0"]
                 [org.postgresql/postgresql "42.2.11"]
                 [honeysql "0.9.10"]
                 [io.sentry/sentry-clj "0.7.2"]
                 [jdbc-ring-session "1.2"]
                 ;; Logging
                 [org.slf4j/jcl-over-slf4j "1.7.25"]
                 [org.slf4j/jul-to-slf4j "1.7.25"]
                 [org.slf4j/log4j-over-slf4j "1.7.25"]
                 [ch.qos.logback/logback-classic "1.2.3"
                  :exclusions [org.slf4j/slf4j-api]]
                 [hikari-cp "2.11.0"]]
  :min-lein-version "2.7.1"
  :main org.clojuriststogether.app.main
  :target-path "target/%s"
  :uberjar-name "clojurists-together-app.jar"
  :aliases {"migrate"  ["run" "-m" "dev/migrate"]
            "rollback" ["run" "-m" "dev/rollback"]}
  :profiles {:uberjar {:aot :all}
             :dev     {:dependencies [[integrant/repl "0.3.1"]]
                       :resource-paths ["dev-resources"]
                       :source-paths ["dev"]}})
