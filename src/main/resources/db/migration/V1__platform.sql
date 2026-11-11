CREATE TABLE app_user (
    username VARCHAR(64) PRIMARY KEY,
    password_hash VARCHAR(100) NOT NULL,
    role VARCHAR(10) NOT NULL CHECK (role IN ('ADMIN', 'USER')),
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    revision INTEGER NOT NULL DEFAULT 1,
    created_at VARCHAR(40) NOT NULL
);
CREATE TABLE scan_target (
    id VARCHAR(36) PRIMARY KEY,
    name VARCHAR(120) NOT NULL,
    host VARCHAR(253) NOT NULL,
    port INTEGER NOT NULL,
    ssh_user VARCHAR(64) NOT NULL,
    private_key TEXT NOT NULL,
    host_key TEXT NOT NULL,
    description VARCHAR(1000) NOT NULL,
    created_by VARCHAR(64) NOT NULL REFERENCES app_user(username),
    created_at VARCHAR(40) NOT NULL
);
CREATE TABLE scan_job (
    id VARCHAR(36) PRIMARY KEY,
    target_id VARCHAR(36) NOT NULL REFERENCES scan_target(id),
    target_name VARCHAR(120) NOT NULL,
    requested_by VARCHAR(64) NOT NULL REFERENCES app_user(username),
    status VARCHAR(20) NOT NULL,
    created_at VARCHAR(40) NOT NULL,
    finished_at VARCHAR(40),
    message VARCHAR(2000) NOT NULL DEFAULT '',
    report_json TEXT,
    finding_count INTEGER NOT NULL DEFAULT 0
);
CREATE INDEX scan_job_created ON scan_job(created_at);
CREATE TABLE catalog_entry (
    entry_key VARCHAR(64) PRIMARY KEY,
    advisory_id VARCHAR(160) NOT NULL,
    kind VARCHAR(10) NOT NULL,
    package_name VARCHAR(500) NOT NULL,
    document TEXT NOT NULL
);
CREATE INDEX catalog_package ON catalog_entry(package_name);
CREATE TABLE catalog_state (
    singleton INTEGER PRIMARY KEY CHECK (singleton = 1),
    imported_at VARCHAR(40),
    source VARCHAR(500) NOT NULL,
    sha256 VARCHAR(64) NOT NULL,
    revision INTEGER NOT NULL
);
INSERT INTO catalog_state VALUES (1, NULL, '', '', 0);
CREATE TABLE update_schedule (
    singleton INTEGER PRIMARY KEY CHECK (singleton = 1),
    enabled BOOLEAN NOT NULL,
    cron VARCHAR(100) NOT NULL,
    zone VARCHAR(80) NOT NULL,
    source VARCHAR(1000) NOT NULL,
    next_run VARCHAR(40)
);
INSERT INTO update_schedule VALUES (1, FALSE, '0 0 3 * * *', 'Asia/Seoul', 'catalog.json', NULL);
CREATE TABLE update_run (
    id VARCHAR(36) PRIMARY KEY,
    started_at VARCHAR(40) NOT NULL,
    source VARCHAR(500) NOT NULL,
    status VARCHAR(20) NOT NULL,
    record_count INTEGER NOT NULL,
    message VARCHAR(1000) NOT NULL
);
