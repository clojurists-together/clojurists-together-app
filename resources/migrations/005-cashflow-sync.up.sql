CREATE TABLE cashflow_stripe_snapshot (
    id INTEGER PRIMARY KEY CHECK (id = 1),
    requested_start DATE NOT NULL,
    history_start DATE,
    data JSONB,
    synced_at TIMESTAMPTZ,
    reconciled_at TIMESTAMPTZ,
    retry_at TIMESTAMPTZ,
    failed BOOLEAN NOT NULL DEFAULT FALSE
);
INSERT INTO cashflow_stripe_snapshot (id, requested_start)
VALUES (1, date_trunc('month', now() AT TIME ZONE 'UTC')::date - INTERVAL '1 year');

CREATE TABLE cashflow_stripe_events (
    id TEXT PRIMARY KEY,
    event_type TEXT NOT NULL,
    object_id TEXT NOT NULL,
    received_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    processed_at TIMESTAMPTZ
);
CREATE INDEX cashflow_stripe_events_pending ON cashflow_stripe_events (received_at)
WHERE processed_at IS NULL;
