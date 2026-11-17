ALTER TABLE asset_profile ADD COLUMN els_contract BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE asset_profile ADD COLUMN els_valid_until VARCHAR(10) NOT NULL DEFAULT '';
ALTER TABLE asset_profile ADD COLUMN els_confirmed_by VARCHAR(64) NOT NULL DEFAULT '';
ALTER TABLE asset_profile ADD COLUMN els_confirmed_at VARCHAR(40) NOT NULL DEFAULT '';
