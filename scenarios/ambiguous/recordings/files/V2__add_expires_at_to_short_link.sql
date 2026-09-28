-- Optional expiry chosen when a link is created (see LinkService). NULL means the link never expires,
-- which is what every link created before this migration keeps: there is deliberately no backfill.
ALTER TABLE short_link ADD COLUMN expires_at TIMESTAMP WITH TIME ZONE NULL;
