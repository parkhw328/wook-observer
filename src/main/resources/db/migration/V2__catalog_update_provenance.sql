ALTER TABLE update_run ADD COLUMN sha256 VARCHAR(64) NOT NULL DEFAULT '';
ALTER TABLE update_run ADD COLUMN catalog_revision INTEGER;
UPDATE update_run
SET sha256 = (SELECT sha256 FROM catalog_state WHERE singleton = 1),
    catalog_revision = (SELECT revision FROM catalog_state WHERE singleton = 1)
WHERE status = 'SUCCESS'
  AND started_at = (SELECT imported_at FROM catalog_state WHERE singleton = 1);
