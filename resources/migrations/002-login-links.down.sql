DROP TABLE login_links;

ALTER TABLE members
    DROP COLUMN founding_member,
    DROP COLUMN created_at,
    DROP COLUMN updated_at,
    DROP COLUMN logo_slug,
    DroP COLUMN preferred_name;
