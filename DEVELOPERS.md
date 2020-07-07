## Development

```console
bin/init-dev-db
lein migrate


```

## Testing webhooks

curl -X POST -d @resources/webhooks/customer.subscription.created.json http://localhost:3000/webhook/stripe
