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
STRIPE_WEBHOOK_SECRET=
```

`STRIPE_WEBHOOK_SECRET` is the signing secret (`whsec_...`) of the Stripe webhook endpoint. Find it in the Stripe Dashboard under Developers, Webhooks. Each endpoint has its own secret, and test mode and live mode are separate endpoints. If the variable is unset, `/webhook/stripe` rejects every request with a 400, so set it in production before deploying.

## Testing webhooks

`/webhook/stripe` only accepts requests signed with `STRIPE_WEBHOOK_SECRET`, and the signature timestamp must be within five minutes. To send the sample event from bash, with the same secret the server is running with:

```bash
payload=resources/webhooks/customer.subscription.created.json
timestamp=$(date +%s)
signature=$(printf '%s.' "$timestamp" | cat - "$payload" | openssl dgst -sha256 -hmac "$STRIPE_WEBHOOK_SECRET" -r | cut -d' ' -f1)
curl -X POST --data-binary @"$payload" -H "Stripe-Signature: t=$timestamp,v1=$signature" http://localhost:3000/webhook/stripe
```

Use `--data-binary`, because `-d` strips newlines and the signature will no longer match.
