## Development

```console
bin/init-dev-db
lein migrate
```

Setup a `.envrc` file with the following info:

```
SENDGRID_API_KEY=
SESSION_COOKIE_KEY=
JDBC_DATABASE_URL= # If you want to use a different dev db to the default set in config.edn
STRIPE_PUBLISHABLE_KEY=
STRIPE_SECRET_KEY=
```

## Testing webhooks

curl -X POST -d @resources/webhooks/customer.subscription.created.json http://localhost:3000/webhook/stripe
