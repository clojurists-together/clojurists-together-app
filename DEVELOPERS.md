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

## Mailchimp sync dry run

Prints what a Mailchimp and Stripe sync would change, and previews the notice emails. It reads the members table in a read-only transaction, only sends `GET` requests, and can't apply or send anything.

```console
lein run -m org.clojuriststogether.app.admin.mailchimp-dry-run [details-file]
```

It needs `MAILCHIMP_API_KEY`, `MAILCHIMP_LIST_ID` and either `MAILCHIMP_SYNC_JDBC_URL` or `JDBC_DATABASE_URL`. To read production from your machine, use `MAILCHIMP_SYNC_JDBC_URL`, not `JDBC_DATABASE_URL`, so nothing else picks up the production database. Set `STRIPE_READ_KEY` to a live key that can read customers to include the Stripe report. The summary has no personal details, but the details file has names and addresses, so keep it out of the repo.

## Testing webhooks

curl -X POST -d @resources/webhooks/customer.subscription.created.json http://localhost:3000/webhook/stripe
