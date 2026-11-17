ALTER TABLE scan_target ADD COLUMN deleted BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE scan_target ADD COLUMN trash_revision INTEGER NOT NULL DEFAULT 0;
ALTER TABLE scan_target ADD COLUMN deleted_by VARCHAR(64);
ALTER TABLE scan_target ADD COLUMN deleted_at VARCHAR(40);
ALTER TABLE asset_service ADD COLUMN deleted BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE asset_service ADD COLUMN deleted_by VARCHAR(64);
ALTER TABLE asset_service ADD COLUMN deleted_at VARCHAR(40);

CREATE TABLE asset_management_event (
    id VARCHAR(36) PRIMARY KEY,
    target_id VARCHAR(36) NOT NULL REFERENCES scan_target(id),
    service_id VARCHAR(36) REFERENCES asset_service(id),
    item_name VARCHAR(160) NOT NULL,
    action VARCHAR(30) NOT NULL,
    actor VARCHAR(64) NOT NULL,
    occurred_at VARCHAR(40) NOT NULL
);
CREATE INDEX asset_management_target ON asset_management_event(target_id, occurred_at);
