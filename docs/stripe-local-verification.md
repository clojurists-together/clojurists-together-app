# Rehearse the Stripe upgrade locally

1. Install Java, Leiningen, the Stripe CLI, and PostgreSQL command-line tools. Put `initdb`, `pg_ctl`, `createdb`, and `psql` on `PATH`.
2. Make ports `3347` and `55437` available.
3. Provide `STRIPE_SECRET_KEY` through your local environment, or keep one test key in the ignored `.envrc` file. The launcher reads only a unique `sk_test_` token from that file. It never sources the shell file. It rejects a supplied live key.
4. Start the rehearsal from the repository root.

```sh
python3 bin/stripe-real-verify bootstrap
```

5. Wait for `checkout-url.txt` in the printed private runtime directory. Open that file's URL in a browser. Complete test Checkout with Stripe's successful test card `4242 4242 4242 4242`, a future expiry date, and any three-digit CVC.
6. Set `runtime` to the printed directory. Confirm Checkout completion, the paid invoice, and the member update.

```sh
runtime=/absolute/private/runtime/directory
python3 bin/stripe-real-verify action "$runtime" complete
python3 bin/stripe-real-verify status "$runtime"
```

7. Open the URL in `flexible-login-url.txt`. Confirm the current plan on the account page. Click **Manage billing details** and confirm the plan in Stripe's hosted portal.

8. Change the subscription Price. Wait for the CLI to deliver the update, then inspect the current Stripe and database state.

```sh
python3 bin/stripe-real-verify action "$runtime" update-price
python3 bin/stripe-real-verify action "$runtime" inspect
```

9. Run the independent classic, backfill, and test-clock cases. Inspect after the clock returns to `ready` and Stripe delivers the renewal events.

```sh
python3 bin/stripe-real-verify action "$runtime" classic
python3 bin/stripe-real-verify action "$runtime" backfill
python3 bin/stripe-real-verify action "$runtime" clock-create
python3 bin/stripe-real-verify action "$runtime" clock-advance
python3 bin/stripe-real-verify action "$runtime" inspect
```

10. Open the URL in `classic-login-url.txt`. Confirm the archived legacy Price on the account page. Click **Manage billing details** and confirm classic billing portal access.
11. Cancel the Checkout subscription. Wait for its deletion event. Confirm its member's `subscription_plan` becomes `null`.

```sh
python3 bin/stripe-real-verify action "$runtime" cancel
python3 bin/stripe-real-verify action "$runtime" inspect
```

12. Save `evidence.json` as the sanitized run report. Keep the other runtime files private. They contain credentials, session URLs, or login tokens.
13. Press Ctrl-C in the bootstrap terminal to stop the owned app, listener, and PostgreSQL process. The private directory and Stripe test objects remain available for inspection.

For infrastructure you already started, write a mode-`0600` `credentials.json` in a private directory with `STRIPE_SECRET_KEY`, `STRIPE_WEBHOOK_SECRET`, and `JDBC_DATABASE_URL`. Use `jdbc:postgresql://127.0.0.1:55437/stripe_upgrade_verify`. Apply all `resources/migrations/*.up.sql` files to that isolated database. Run the Stripe CLI listener with `--latest` and forward subscription events to `http://127.0.0.1:3347/webhook/stripe`. Start the app with `python3 bin/stripe-real-verify start "$runtime"`.
