CREATE EXTENSION IF NOT EXISTS citext WITH SCHEMA public;

CREATE TABLE members (
    id SERIAL PRIMARY KEY,
    email CITEXT UNIQUE,
    person_name TEXT NOT NULL,
    member_type TEXT NOT NULL,
    stripe_customer_id TEXT NOT NULL,
    subscription_plan TEXT,
    organization_name TEXT,
    organization_url TEXT,
    invoicing_email TEXT,
    updates_email TEXT
);
