CREATE TYPE COLLECTION_METHOD AS ENUM ('charge_automatically', 'send_invoice');

CREATE TABLE subscriptions(
    id TEXT PRIMARY KEY NOT NULL,
    customer_id TEXT NOT NULL,
    member_id INTEGER REFERENCES members(id),
    status TEXT NOT NULL,
    plan_id TEXT NOT NULL,
    product_id TEXT NOT NULL,
    start_date TIMESTAMPTZ NOT NULL,
    ended_at TIMESTAMPTZ NOT NULL,
    collection_method COLLECTION_METHOD NOT NULL,
    json_payload JSONB NOT NULL
);
