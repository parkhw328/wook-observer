ALTER TABLE scan_target ADD COLUMN collection_mode VARCHAR(10) NOT NULL DEFAULT 'SSH';
ALTER TABLE scan_target ADD CONSTRAINT scan_target_collection_mode CHECK (collection_mode IN ('SSH', 'UPLOAD'));
ALTER TABLE scan_job ADD COLUMN collection_method VARCHAR(10) NOT NULL DEFAULT 'SSH';
ALTER TABLE scan_job ADD COLUMN import_sha256 VARCHAR(64);
CREATE UNIQUE INDEX scan_job_import_sha ON scan_job(import_sha256);
CREATE TABLE offline_import_lock (singleton INTEGER PRIMARY KEY CHECK (singleton = 1));
INSERT INTO offline_import_lock VALUES (1);
