## REPL

```console
lein repl
```

```clojure
(require 'dev)
(in-ns 'dev)
(reset)
```

## Testing webhooks

curl -X POST -d @resources/webhooks/customer.subscription.created.json http://localhost:3000/webhook/stripe

stripe listen --forward-to http://localhost:3000/webhook/stripe

## Sign up

http://localhost:3000/register/developer?plan=plan_GY8ntpqaHEUlza
http://localhost:3000/register/company?plan=plan_GY8ntpqaHEUlza
