CREATE TABLE stripe_slack_notifications (
    event_id TEXT PRIMARY KEY,
    payload JSONB NOT NULL,
    message JSONB,
    received_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    retry_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    attempts INTEGER NOT NULL DEFAULT 0,
    delivered_at TIMESTAMPTZ,
    last_error TEXT
);
CREATE INDEX stripe_slack_notifications_pending ON stripe_slack_notifications (retry_at)
WHERE delivered_at IS NULL;
