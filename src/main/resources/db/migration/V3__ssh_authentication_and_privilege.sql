ALTER TABLE scan_target ADD COLUMN auth_type VARCHAR(16) NOT NULL DEFAULT 'KEY';
ALTER TABLE scan_target ADD COLUMN ssh_password TEXT NOT NULL DEFAULT '';
ALTER TABLE scan_target ADD COLUMN key_passphrase TEXT NOT NULL DEFAULT '';
ALTER TABLE scan_target ADD COLUMN privilege_mode VARCHAR(20) NOT NULL DEFAULT 'NONE';
ALTER TABLE scan_target ADD COLUMN privilege_password TEXT NOT NULL DEFAULT '';
ALTER TABLE scan_target ADD CONSTRAINT scan_target_auth_type CHECK (auth_type IN ('KEY', 'PASSWORD'));
ALTER TABLE scan_target ADD CONSTRAINT scan_target_privilege_mode CHECK (privilege_mode IN ('NONE', 'SU_ROOT', 'SUDO', 'SUDO_NOPASSWD'));
