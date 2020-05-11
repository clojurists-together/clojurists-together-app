CREATE EXTENSION IF NOT EXISTS citext WITH SCHEMA public;

CREATE TABLE members (
    email CITEXT PRIMARY KEY,
    person_name TEXT NOT NULL,
    member_type TEXT NOT NULL,
    stripe_customer_id TEXT NOT NULL,
    subscription_plan TEXT
);
