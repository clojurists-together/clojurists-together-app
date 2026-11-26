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
```

## Admin cashflow forecast

Run the database migrations before deploying. Grant or revoke admin access manually:

```sql
UPDATE members SET is_admin = TRUE WHERE email = 'admin@example.org';
UPDATE members SET is_admin = FALSE WHERE email = 'admin@example.org';
```

Admins sign in using the existing email link and open `/admin/cashflow` from
Manage account. Both the page and `/admin/cashflow.xlsx` recheck the database role
on every request and disable caching. Signup and account updates cannot set roles.

### Cloudflare Access setup

Production also requires a valid Cloudflare Access application JWT on both admin
routes. Access adds a separate email one-time code check; it does not create a member session.
Admins still use the existing email-link login and must have `members.is_admin = TRUE`.
The Access identity and member session are checked independently. Public member
routes and Stripe webhooks keep their existing authentication.

Configure this externally before enabling the admin pages in production:

1. Keep `members.clojuriststogether.org` proxied through Cloudflare DNS. Create a
   self-hosted Access application for `members.clojuriststogether.org/admin/*`.
   This path covers both `/admin/cashflow` and `/admin/cashflow.xlsx`. If a bare
   `/admin` route is added later, protect that path separately too.
2. Configure the [One-time PIN identity provider](https://developers.cloudflare.com/cloudflare-one/integrations/identity-providers/one-time-pin/).
   Cloudflare emails a single-use code to approved addresses; no Google OAuth
   client or client secret is needed.
3. Select only One-time PIN for this Access application. Disable **Accept all
   available identity providers** and use a 24-hour session duration.
4. Create an Allow policy with an **Include → Emails** rule containing exactly
   `daniel@clojuriststogether.org` and `kdavis@clojuriststogether.org`.
   Do not use Everyone, an entire email domain, Bypass, or service-token policies
   for these admin pages.
5. Copy the application's audience (AUD) tag and team origin into the app's
   environment:

   ```
   CLOUDFLARE_ACCESS_ISSUER=https://clojurists-together.cloudflareaccess.com
   CLOUDFLARE_ACCESS_AUDIENCE=46e0a7ed0bd5c26add42c8341362999a156e7b4038b997c517146352bddf5cb3
   ```

The issuer and audience are identifiers, not secrets. The issuer must be an HTTPS
`TEAM.cloudflareaccess.com` origin. The app verifies the RS256 signature against
that team's `/cdn-cgi/access/certs`, exact issuer, audience, expiry, optional
not-before time, and a signed email claim. It does not trust unsigned email headers.
See [Cloudflare's JWT validation guidance](https://developers.cloudflare.com/cloudflare-one/access-controls/applications/http-apps/authorization-cookie/validating-json/).
Keys are cached for five minutes and refreshed for an unknown key ID; retrieval
has two-second connect/read limits and a 100 KB response limit. Invalid tokens
return 403; unavailable keys or an exhausted JWKS refresh budget return 503.
The library allows at most two key fetches per 30 seconds to limit unknown-key
refresh traffic. These responses disable caching.

Missing production issuer or audience disables only the admin routes with 503;
it does not disable the public app. Only explicit `:dev` and `:test` profiles may
omit both settings. Supplying both settings enforces Access locally too; partial
local configuration is rejected. The local read-only cashflow preview launcher
remains separate and does not use these production authentication gates.

Cloudflare's email-code provider, application, two-address allow policy, and the
production environment variables are configured. The member hostname is proxied;
production enforces Access JWT validation in addition to the member admin role.

The default view covers January of this year through December of next year.
Completed months contain actual subscription payments less recorded Stripe fees.
The current month projects receipts already collected plus remaining renewals;
subsequent months forecast renewals. Columns are labelled Actual or Forecast in
the app and Excel. Live Stripe subscription data supplies amounts, quantities, billing dates, trials,
discounts and scheduled cancellations. Annual charges appear in renewal months.
The forecast uses UTC and assumes successful payment. Currencies are kept separate.
The sticky company column shows current MRR, rounded to the nearest dollar, after
recurring discounts and before fees. Cancellation scenarios leave this reference unchanged.
Forecast reads use Stripe API version `2020-08-27` so subscription and invoice fields
remain stable across Stripe Java SDK upgrades.
Historical receipts include payments for subscriptions that have since cancelled.
Payments with no unique member match are included under Other subscription receipts.
Actuals use the balance transaction's net amount and settlement currency, dated by
the successful charge. Refunds, disputes, payments outside Stripe and separately
billed fees are excluded. Missing recorded payment fees block the report rather
than substituting an estimate for an actual.

Future net receipts estimate fees separately for each Stripe customer and currency,
using the three latest eligible payments from a rolling 12-month history (including
the whole first month). Payments with currency conversion are excluded from this
fee sample. Matching gross amounts use the average recorded fee; other amounts use
total sample fees divided by total sample gross receipts. No fixed fee is added to
the learned estimate, since recorded fees already include it. History is fetched
independently of the selected report year so next-year-only reports still learn fees.

Members without eligible history use an editable fallback percentage (initially
5%, based on the reference budget) and optional fixed fee per payment.
The fixed fee is expressed in each currency's major units. These are estimates,
not Stripe's actual negotiated fees. These inputs affect only future receipts.
Payout timing is ignored. Forecast taxes, refunds,
unpaid invoices, expenses, and future plan/quantity changes are outside this version.
Manual-collection renewals are included at their expected payment date: renewal plus
`days_until_due` (zero if unset), with estimated fees deducted. Existing unpaid
invoices are not treated as new renewals; void invoices are never projected as debts.
Schedules with future or unknown changes, paused collection, usage/tiered pricing, and other
unsupported subscriptions appear as exclusions rather than silently counting as zero.
Exclusions link customer names to Stripe and show monthly-equivalent subscription
amounts, including recurring discounts and before fees. Collected payments for
excluded subscriptions are still included in actuals.
They also show the latest unpaid invoice's remaining amount, actual due date and
next payment attempt when Stripe supplies them. Otherwise, they show the current
plan's billing amount and expected due date (renewal plus invoice payment terms).
Missing invoice due dates are labelled as unset; invoice issue dates and retry dates
are shown separately. Expected dates are not invented for schedules or paused billing.
The Excel assumptions sheet includes billing details and each member's fee sample totals.

Cancellation scenarios are local to the query and export: the selected month is
the first month without future receipts. Controls show only company members, and
actual collected receipts never change. They never update Stripe or the member database.
Within the expanded Membership receipts rows, current and future company amounts
are buttons that set the cancellation month and resubmit the forecast. Past actuals
and developer totals are not clickable. The expanded table stays open, and a Reset
button beside each simulated cancellation clears that company's scenario.
Export submits the current form and reuses the Stripe snapshot for up to five minutes;
one snapshot is cached in process memory, with its original cutoff time. The workbook
includes the snapshot time, fee assumptions, scenario months and exclusions.
Apache POI writes the workbook, including row groups, frozen panes and recalculable
scenario totals. Page styles and event handlers live in `resources/public/css/cashflow.css`
and `resources/public/js/cashflow.js`.

Run `lein test` for the forecast, authorization and export tests. No live Stripe
credentials or database writes are needed for these tests.

## Testing webhooks

curl -X POST -d @resources/webhooks/customer.subscription.created.json http://localhost:3000/webhook/stripe

## Reloadable cashflow preview

Run `lein trampoline run -m cashflow-preview` with `JDBC_DATABASE_URL` and
`STRIPE_SECRET_KEY` set. The
launcher lives in `dev/cashflow_preview.clj`; it binds only to `127.0.0.1:3000`,
asserts a read-only database connection, and accepts only GET requests. Its temporary
sign-in URL is written to `target/cashflow-preview/login-url`. This preview entry
point is for local development, not deployment.

The launcher exposes a Clojure socket REPL at `127.0.0.1:5555`. Connect with
`nc 127.0.0.1 5555`, then evaluate `(cashflow-preview/reload!)` to explicitly reload
calculation, Stripe client, Excel export, and page namespaces in dependency order.
Refresh the browser afterward. Normal HTTP requests never reload or compile code.
`defonce` caches, the database pool, and the session store stay alive. Static JS/CSS
is served from `resources/public` without rebuilding. The request handler is
constructed afresh, so handler edits take effect after an explicit reload too.
Compilation errors are reported in the REPL; fix the source and call `reload!` again.
Dependency, environment, or launcher changes still require a restart. The socket
REPL is a local development interface and must never be exposed on a public address.

Forecast tables grow vertically in the document. A visual-only, aria-hidden header
copy pins to the window while its table passes behind it. Horizontal scrolling stays
within each wide table and is synchronised with the floating header. The original
semantic table remains the only one exposed to assistive technology.

Stripe schedules are expanded with subscriptions. A subscription in the last active
phase of a release schedule is forecast normally when the schedule ends at or before
the next renewal. Earlier phases are historical; they do not justify excluding future
renewals. Schedules that cancel, have future phases, or are not expanded remain excluded.

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

`/webhook/stripe` only accepts requests signed with `STRIPE_WEBHOOK_SECRET`. To send the sample event, signed with the same secret the server is running with:

```console
bin/send-test-webhook
```

### Cashflow Stripe sync

The forecast and Excel export read a durable snapshot from Postgres; neither calls
Stripe. Migration 005 creates the snapshot and a webhook work queue. The production
Integrant worker starts automatically, checks every 30 seconds, and uses a Postgres
advisory lock so only one worker syncs during overlapping deploys or across dynos.
Development does not start the worker automatically.

On first deployment, the worker imports history from twelve months before the
current month. Requesting an earlier forecast year records a request to extend
that history. Until coverage is available the page returns a loading view and
refreshes every five seconds; an export returns 503 with Retry-After.

Verified webhooks insert an event ID, type, and object ID into the durable queue.
Duplicate event IDs do not create extra work. The worker fetches current Stripe
objects rather than trusting event order. It refreshes subscriptions and the last
seven days of charges on queued work or after five minutes. Changed charges older
than that overlap are fetched individually. Once per day it reconciles the entire
stored history range. Stripe payment fees that are not yet available cause a retry,
not an estimated historical amount. Failed syncs preserve the previous snapshot,
leave events pending, and retry after 60 seconds. The page displays the last sync
time and any failure. All Stripe access from this sync is read-only.

Subscription and receipt changes publish in one short database transaction. Events
received during a sync remain pending for the next pass. Restarts retain both the
last complete snapshot and unfinished work. Logs include timings/counts and error
classes, without logging API responses or credentials.

The existing `/webhook/stripe` endpoint must subscribe to the event types in
`cashflow-sync/event-types`, in addition to its existing membership events. It stays
outside Cloudflare Access. No separate worker dyno, Redis, or Scheduler add-on is
needed; at least one web dyno must be running for background syncs to execute.

Run the Postgres integration tests with a disposable local database:

```
CASHFLOW_SYNC_TEST_DATABASE_URL=jdbc:postgresql://localhost:5432/test lein test
```

The integration test creates and drops its own uniquely named schema. Without
that variable, only the database integration test is skipped. Unit and signed
webhook tests still run.
