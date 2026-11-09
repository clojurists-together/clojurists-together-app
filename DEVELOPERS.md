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
STRIPE_SECRET_KEY=
STRIPE_WEBHOOK_SECRET=
```

## Testing webhooks

`/webhook/stripe` only accepts requests signed with `STRIPE_WEBHOOK_SECRET`. To send the sample event, signed with the same secret the server is running with:

```console
bin/send-test-webhook
```
