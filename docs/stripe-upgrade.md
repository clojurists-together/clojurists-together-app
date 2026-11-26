# Roll out the Stripe upgrade

This change pins `com.stripe/stripe-java` to **34.2.0**, the stable Maven release checked on 2026-11-26. Its fixed API version is **2026-11-18.endive**. The SDK sends that version independently of the account default. The regression tests check the SDK constants and the actual HTTP `Stripe-Version` header.

New Checkout subscriptions explicitly use `mode=subscription`, `line_items.price`, quantity 1, and `subscription_data.billing_mode.type=flexible`. Administrative backfill creates flexible subscriptions through `items.price` and retains its 30-day trial. Billing portal creation is unchanged.

Existing subscriptions are not migrated. Existing `plan_...` IDs, including custom IDs such as `plan_legacy-monthly` and `plan_legacy_monthly`, work as recurring Prices. Public `plan` parameters accept `plan_` or `price_` with a nonblank suffix. Signed webhooks also preserve customer-supplied legacy IDs without those prefixes, such as `custom-monthly`. Identifiers are passed through unchanged; stored `subscription_plan` values, public `plan` parameters, and membership tier mappings stay intact. Account pages can retrieve archived fixed recurring Prices. New offers list active fixed per-unit licensed recurring Prices. One-time, metered, and tiered Prices are outside this membership UI.

## Validate in Stripe test mode

The regression tests exercise the real Java SDK against local HTTP responses and signed events through the application's Ring route tree. A separate local rehearsal now uses the real Stripe test API, hosted Checkout, genuine Stripe CLI signatures, and isolated PostgreSQL. See the [rerun procedure](stripe-local-verification.md) and [run evidence](../.audit/stripe-real-verification.json). No deployment, production endpoint change, or live subscription migration was performed.

The real test run checks flexible Checkout, a paid initial invoice, signed membership updates, a Price change, cancellation, a classic fixture with an archived legacy Plan, portal creation, the administrative backfill's 30-day trial, and a paid renewal through a test clock. The test fixture for classic billing is newly created. Existing historical subscription state and backfill trial-end collection still need their own checks.

The account's canonical stored events remain at API version `2020-03-02`. The Stripe CLI's `--latest` option renders the genuinely delivered payloads as `2026-11-18.endive`. The rehearsal also replays three unmodified canonical `2020-03-02` payloads with locally generated signatures. Created, updated, and deleted events return HTTP 200 and produce the expected Price, replacement Price, and empty membership in PostgreSQL. Those replays are local signature tests with actual Stripe payloads, separate from the genuine CLI deliveries.

A negative control also sent the old `subscription_data[items][0][plan]` request shape without `mode` to API `2026-11-18.endive`. Stripe accepted it with HTTP 200. The test session was expired without payment. This rehearsal supports the modern explicit flexible request contract. It does not prove Stripe has removed compatibility with that older shape.

Use the following checklist for a separate target account or before production rollout:

1. Configure test credentials and the test endpoint signing secret. Use API version `2026-11-18.endive` for the endpoint.
2. Open developer and company registration with both an existing `plan_...` ID and a `price_...` ID. Check the product, currency, amount, and billing interval.
3. Complete hosted Checkout with a test card. Confirm the new subscription has `billing_mode.type=flexible` and one item with the chosen Price. Confirm the redirect and membership update.
4. Check an existing classic subscription and an archived Price on the account page. Confirm billing portal access still works.
5. Deliver signed created, updated, and deleted events for classic and flexible subscriptions. Confirm the membership field stores the exact Price ID and deletion clears it. Replay a historical event with top-level `plan.id`.
6. Deliver an event with a missing Price ID or multiple subscription items. Confirm HTTP 400 and no membership change. Investigate rejected events before replaying them.
7. In a disposable test customer, check the administrative backfill's flexible mode and unchanged 30-day trial. Check cancellation and trial-end invoice behavior.

## Release and webhook version order

1. Record the deployed application revision, webhook endpoint API version, subscribed event types, signing secret source, and recent delivery health in Stripe Workbench.
2. Deploy this application after the sandbox checks pass. Leave the existing webhook endpoint version in place initially. The parser accepts historical top-level `plan.id` and modern `items.data[0].price.id`.
3. Confirm successful signed deliveries and compare stored Price IDs with Stripe. The application rejects missing, malformed, conflicting, truncated, and multiple-item identifiers without clearing membership.
4. Upgrade the subscription webhook endpoint to `2026-11-18.endive` through Stripe Workbench. Keep its created, updated, and deleted event subscriptions. If you replace the endpoint, update the signing secret with it.
5. Monitor deliveries, HTTP 400 responses, registration, account pages, and billing portal access. Replay failed events only after correcting their cause.

Changing the application's SDK does not change webhook endpoint versions or the Stripe account default. Avoid a broad account-default upgrade until other Stripe consumers have been reviewed.

## Existing subscription migration and rollback limits

Do not migrate live subscriptions as part of this release. A later migration needs a separate approved customer inventory, a sandbox rehearsal, and review of invoices, discounts, trials, proration behavior, and any subscription schedules. Confirm each resulting subscription and delivery before expanding the batch.

Stripe cannot migrate a flexible subscription back to classic mode. Reverting the application or webhook version does not undo new flexible subscriptions. The pre-upgrade webhook parser reads only top-level `plan.id`, so reverting to it after flexible subscriptions exist can clear memberships on modern events. Keep the dual-format parser and modern Checkout request shape if reverting other application changes. Record the endpoint's prior version and follow Stripe's endpoint upgrade procedure if its version must be restored.

## Existing membership lifecycle limits

The app treats a present subscription Price ID as membership. It does not distinguish payment failure, `incomplete`, `past_due`, or other subscription statuses. This upgrade preserves that policy. Membership updates match the customer ID rather than a stored subscription ID. An old subscription's deletion can therefore clear a newer membership if deliveries arrive out of order. There is no event-ID deduplication or event-time ordering. Multiple subscriptions per customer and mixed-item subscriptions need a separate membership lifecycle design.

## Sources

- [Maven release metadata](https://repo.maven.apache.org/maven2/com/stripe/stripe-java/maven-metadata.xml) and [34.2.0 source artifact](https://repo.maven.apache.org/maven2/com/stripe/stripe-java/34.2.0/stripe-java-34.2.0-sources.jar) pin the release and API constants.
- [Stripe API upgrades](https://docs.stripe.com/upgrades) describes the fixed Java SDK version and separate webhook endpoint upgrade.
- [Checkout Prices migration](https://docs.stripe.com/payments/checkout/migrating-prices) documents `line_items`, subscription mode, and compatibility with existing Plan IDs.
- [Flexible billing mode](https://docs.stripe.com/billing/subscriptions/billing-mode) documents creation, migration, and the restriction on returning to classic mode.
- [Endive changelog](https://docs.stripe.com/changelog/endive) records the target API release.
