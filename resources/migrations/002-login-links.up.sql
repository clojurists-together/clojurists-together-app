CREATE TABLE login_links
(
    id         UUID PRIMARY KEY,
    member_id  INTEGER REFERENCES members(id),
    created_at timestamptz DEFAULT now() NOT NULL,
    expires_at timestamptz DEFAULT NOW() + interval '10 minutes' NOT NULL
);

ALTER TABLE members
    ADD COLUMN founding_member BOOLEAN     DEFAULT FALSE NOT NULL,
    ADD COLUMN created_at      TIMESTAMPTZ DEFAULT now() NOT NULL,
    ADD COLUMN updated_at      TIMESTAMPTZ DEFAULT now() NOT NULL,
    ADD COLUMN logo_slug       TEXT NULL,
    ADD COLUMN preferred_name  TEXT DEFAULT '' NOT NULL;
