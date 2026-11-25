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

## Admin cashflow forecast

Run the database migrations before deploying. Grant or revoke admin access manually:

```sql
UPDATE members SET is_admin = TRUE WHERE email = 'admin@example.org';
UPDATE members SET is_admin = FALSE WHERE email = 'admin@example.org';
```

Admins sign in using the existing email link and open `/admin/cashflow` from
Manage account. Both the page and `/admin/cashflow.xlsx` recheck the database role
on every request and disable caching. Signup and account updates cannot set roles.

The default view covers January of this year through December of next year.
Completed months contain actual subscription payments less recorded Stripe fees.
The current month projects receipts already collected plus remaining renewals;
subsequent months forecast renewals. Columns are labelled Actual or Forecast in
the app and Excel. Live Stripe subscription data supplies amounts, quantities, billing dates, trials,
discounts and scheduled cancellations. Annual charges appear in renewal months.
The forecast uses UTC and assumes successful payment. Currencies are kept separate.
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
