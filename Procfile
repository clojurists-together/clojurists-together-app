web: java $JVM_OPTS -jar target/uberjar/clojurists-together-app.jar prod
release: java $JVM_OPTS -cp target/uberjar/clojurists-together-app.jar org.clojuriststogether.app.admin.migrate "prod"
