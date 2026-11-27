## Development

```console
bin/init-dev-db
bin/install-git-hooks
lein migrate
```

Setup a `.envrc` file with the following info:

```
SENDGRID_API_KEY=
SESSION_COOKIE_KEY=
JDBC_DATABASE_URL= # If you want to use a different dev db to the default set in config.edn
STRIPE_SECRET_KEY=
STRIPE_WEBHOOK_SECRET=
STRIPE_WEBHOOK_SECONDARY_SECRET= # Optional during webhook endpoint replacement
STRIPE_SLACK_WEBHOOK_URL= # Slack incoming webhook for subscription notifications
```

## Mailchimp sync dry run

Prints what a Mailchimp and Stripe sync would change, and previews the notice emails. It reads the members table in a read-only transaction, only sends `GET` requests, and can't apply or send anything.

```console
lein run -m org.clojuriststogether.app.admin.mailchimp-dry-run [details-file]
```

It needs `MAILCHIMP_API_KEY`, `MAILCHIMP_LIST_ID` and either `MAILCHIMP_SYNC_JDBC_URL` or `JDBC_DATABASE_URL`. To read production from your machine, use `MAILCHIMP_SYNC_JDBC_URL`, not `JDBC_DATABASE_URL`, so nothing else picks up the production database. Set `STRIPE_READ_KEY` to a live key that can read customers to include the Stripe report. The summary has no personal details, but the details file has names and addresses, so keep it out of the repo.

## Mailchimp sync

Brings Mailchimp in line with the app. Run the dry run first, because this one changes things.

```console
lein run -m org.clojuriststogether.app.admin.mailchimp-apply
```

`--send-notices` also emails members whose mailing address changed, and needs `SENDGRID_API_KEY`. `--stripe` also updates Stripe customer emails to the app's billing email, and needs `STRIPE_SECRET_KEY`. The run stops before changing anything if there are no members with a plan, or if it would remove the `Member` tag more than 25 times. A failed change is logged and retried on the next run.

## Testing webhooks

`/webhook/stripe` accepts requests signed with `STRIPE_WEBHOOK_SECRET` or the optional
`STRIPE_WEBHOOK_SECONDARY_SECRET`. Blank values are ignored; no configured signing
secret rejects every request. To send the sample event, signed with the primary
secret the server is running with:

```console
bin/send-test-webhook
```

For an endpoint API version upgrade, deploy support for both secrets first. Create
the replacement endpoint with the same URL and required event types, then
immediately disable it. Stripe's creation API enables new endpoints by default.
Configure its signing secret as `STRIPE_WEBHOOK_SECONDARY_SECRET` before enabling
it. Verify a real signed delivery and the expected database effects before disabling
the old endpoint. Then promote the new secret to `STRIPE_WEBHOOK_SECRET` and remove
the secondary variable in the same configuration update. Keep the disabled old
endpoint for rollback. Never put signing secrets in source files or logs.

If rollback is needed after promotion, restore both secrets before re-enabling the
old endpoint. Keep the new endpoint active until old-endpoint delivery succeeds,
then disable the new endpoint and restore the old primary-only configuration.

## Subscription notifications in Slack

Configure a Slack app with only the `incoming-webhook` scope and authorize it for
`#stripe-logs`. Store its incoming webhook URL in `STRIPE_SLACK_WEBHOOK_URL`; never
commit or log the URL. No bot token or Slack message-reading permission is needed.
The production Stripe endpoint subscribes to `customer.subscription.created`,
`customer.subscription.updated`, `customer.subscription.deleted`, and `customer.created` at
`2026-11-18.endive`.

The signed application webhook commits membership changes and a notification
outbox row in one transaction. Scheduled cancellations retain the membership
until the final deletion event. The worker posts customer name/email, product,
Price nickname (or ID), quantity-adjusted list amount, currency, billing interval,
and cancellation date when present. Amounts are before discounts and taxes;
usage-based prices are labelled instead of estimated. Unrelated metadata changes
do not generate a notification. Cancellation requests, date changes, reversals,
and final cancellation are distinct messages.

Subscription updates use the pure `subscription-events/update-details` summary for
both queue filtering and rendering. Partial previous maps are restored from the
event snapshot before comparing. Item comparisons use price identity, amount,
recurrence, quantity, discounts, taxes, thresholds, and trial settings. Item order,
price/product expansion, nickname/archive flags, metadata, and billing period dates
do not create changes. Status, cancellation, trial, collection pause, membership,
discount, tax, billing settings, and scheduling updates have distinct headings.
The status diff makes payment headings explicit; no invoice lookup is performed.

Routine updates remain silent for metadata, latest invoice, period timestamps,
updated/created timestamps, payment method/source/settings, pending setup intent,
cancellation feedback/comments, canceled/ended timestamps, object identity,
livemode, and application identity. Financial ownership and unknown non-routine
fields alert with field names and withheld values. New top-level fields therefore cannot
silently disappear or dump arbitrary data into Slack. Changes are escaped and
bounded to 12 lines, four memberships per side, six current membership cards,
and an 8,000-character fallback. Every message links to the Stripe event for
complete inspection. Customer contact details remain in the existing identity
section; change details never dump customer, payment, metadata, or free-text objects.

New-customer notifications are retained from the legacy integration.

The worker enriches customer and product display names using the pinned Stripe
SDK; it never fetches current subscription state to replace the event snapshot.
Duplicate Stripe event IDs insert one outbox row. Failed sends retry after one
minute, and successful sends are not repeated. A network timeout or crash after
Slack accepts a message but before the database records success can still cause
a duplicate; incoming webhooks have no idempotency key. Use the outbox event ID
to diagnose retries. `last_error` stores only exception class/HTTP status. Monitor pending rows and attempts in `stripe_slack_notifications`.

With the URL unset, notification work remains queued. Run the real local HTTP,
Slack receiver and PostgreSQL tests with a disposable database:

```console
SLACK_TEST_DATABASE_URL=jdbc:postgresql://localhost:5432/test lein test
```

Migration 007 removes the retired cashflow cache tables. Historical migrations
remain to preserve the migration history; no cashflow runtime, routes, assets,
background polling, or old-version API client remain.
