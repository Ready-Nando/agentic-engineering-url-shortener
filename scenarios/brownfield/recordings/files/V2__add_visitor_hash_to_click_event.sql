-- Day-scoped keyed hash identifying a visitor for unique-visitor statistics (see VisitorFingerprint).
-- Never holds raw personal data. NULL for clicks recorded before this migration, which unique-visitor
-- counts therefore ignore while totals still include them.
ALTER TABLE click_event ADD COLUMN visitor_hash CHAR(64) NULL;
