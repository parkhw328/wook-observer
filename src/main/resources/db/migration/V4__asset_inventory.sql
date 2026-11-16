CREATE TABLE asset_profile (
    target_id VARCHAR(36) PRIMARY KEY REFERENCES scan_target(id),
    asset_code VARCHAR(120) NOT NULL DEFAULT '',
    environment VARCHAR(20) NOT NULL DEFAULT 'UNSPECIFIED',
    owner VARCHAR(120) NOT NULL DEFAULT '',
    location VARCHAR(200) NOT NULL DEFAULT '',
    purpose VARCHAR(1000) NOT NULL DEFAULT '',
    lifecycle VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    notes VARCHAR(4000) NOT NULL DEFAULT '',
    revision INTEGER NOT NULL DEFAULT 1,
    updated_by VARCHAR(64) NOT NULL,
    updated_at VARCHAR(40) NOT NULL
);
CREATE TABLE asset_inventory (
    target_id VARCHAR(36) PRIMARY KEY REFERENCES scan_target(id),
    job_id VARCHAR(36) NOT NULL REFERENCES scan_job(id),
    observed_at VARCHAR(40) NOT NULL,
    summary_json TEXT NOT NULL
);
CREATE TABLE asset_service (
    id VARCHAR(36) PRIMARY KEY,
    target_id VARCHAR(36) NOT NULL REFERENCES scan_target(id),
    source_key VARCHAR(80) NOT NULL,
    source VARCHAR(10) NOT NULL CHECK (source IN ('AUTO', 'MANUAL')),
    observed_json TEXT,
    last_seen_job_id VARCHAR(36) REFERENCES scan_job(id),
    last_seen_at VARCHAR(40),
    name VARCHAR(160) NOT NULL DEFAULT '',
    bindings VARCHAR(2000) NOT NULL DEFAULT '',
    owner VARCHAR(120) NOT NULL DEFAULT '',
    software VARCHAR(200) NOT NULL DEFAULT '',
    service_type VARCHAR(20) NOT NULL DEFAULT 'OTHER',
    software_version VARCHAR(200) NOT NULL DEFAULT '',
    run_user VARCHAR(120) NOT NULL DEFAULT '',
    install_path VARCHAR(2000) NOT NULL DEFAULT '',
    data_path VARCHAR(2000) NOT NULL DEFAULT '',
    log_path VARCHAR(2000) NOT NULL DEFAULT '',
    start_command VARCHAR(4000) NOT NULL DEFAULT '',
    stop_command VARCHAR(4000) NOT NULL DEFAULT '',
    status_command VARCHAR(4000) NOT NULL DEFAULT '',
    dependencies VARCHAR(4000) NOT NULL DEFAULT '',
    notes VARCHAR(4000) NOT NULL DEFAULT '',
    archived BOOLEAN NOT NULL DEFAULT FALSE,
    revision INTEGER NOT NULL DEFAULT 0,
    updated_by VARCHAR(64),
    updated_at VARCHAR(40),
    UNIQUE (target_id, source_key)
);
CREATE INDEX asset_service_target ON asset_service(target_id);
CREATE INDEX scan_job_target_created ON scan_job(target_id, created_at);
