package com.wook.observer.platform;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
@Profile("!collector")
public class Targets {
    private static final String PUBLIC_COLUMNS = "id, name, host, port, ssh_user, description, created_by, created_at, auth_type, privilege_mode";
    private final Store store;
    private final SecretVault vault;
    public Targets(Store store, SecretVault vault) { this.store = store; this.vault = vault; }

    public List<Map<String, Object>> list() {
        return store.rows("SELECT " + PUBLIC_COLUMNS + " FROM scan_target ORDER BY created_at DESC");
    }

    public Map<String, Object> details(String id) {
        // Explicit projection: newly added credential columns must never reach the browser.
        var safe = new LinkedHashMap<>(store.one("SELECT " + PUBLIC_COLUMNS + ", host_key FROM scan_target WHERE id = ?", id));
        var secret = credentials(id);
        safe.put("hostKeyFingerprint", HostKeys.describe(Store.string(safe, "host_key")).fingerprint());
        safe.put("hasPrivateKey", !Store.string(secret, "private_key").isEmpty());
        safe.put("hasSshPassword", !Store.string(secret, "ssh_password").isEmpty());
        safe.put("hasKeyPassphrase", !Store.string(secret, "key_passphrase").isEmpty());
        safe.put("hasPrivilegePassword", !Store.string(secret, "privilege_password").isEmpty());
        return safe;
    }

    public synchronized String save(String id, Input input, String actor) {
        validate(input);
        String hostKey = HostKeys.normalize(input.hostKey());
        boolean create = id == null;
        var existing = create ? Map.<String, Object>of() : credentials(id);
        String auth = input.authType() == null ? "KEY" : input.authType();
        String privilege = input.privilegeMode() == null ? "NONE" : input.privilegeMode();
        if (!Set.of("KEY", "PASSWORD").contains(auth) || !Set.of("NONE", "SU_ROOT", "SUDO", "SUDO_NOPASSWD").contains(privilege)) {
            throw new ApiProblem(400, "지원하는 SSH 인증 방식과 점검 권한을 선택하세요.");
        }
        String key = "", password = "", passphrase = "", elevationPassword = "";
        if ("KEY".equals(auth)) {
            String supplied = input.privateKey();
            if (supplied != null && !supplied.isBlank()) {
                if (supplied.length() > 32_000 || !supplied.strip().startsWith("-----BEGIN ") || !supplied.contains("PRIVATE KEY-----")) {
                    throw new ApiProblem(400, "PEM/OpenSSH 형식의 SSH 개인 키를 입력하세요.");
                }
                key = vault.encrypt(supplied.strip() + "\n");
            } else if ("KEY".equals(Store.string(existing, "auth_type"))) { key = Store.string(existing, "private_key"); }
            if (key.isEmpty()) { throw new ApiProblem(400, "SSH 개인 키가 필요합니다."); }
            // A replacement key must not inherit the previous key's passphrase.
            passphrase = secret(input.keyPassphrase(), existing, "key_passphrase",
                    "KEY".equals(Store.string(existing, "auth_type")) && (supplied == null || supplied.isBlank()), false, "개인 키 암호");
        } else {
            password = secret(input.sshPassword(), existing, "ssh_password", "PASSWORD".equals(Store.string(existing, "auth_type")), true, "SSH 비밀번호");
        }
        if (Set.of("SU_ROOT", "SUDO").contains(privilege)) {
            elevationPassword = secret(input.privilegePassword(), existing, "privilege_password",
                    privilege.equals(Store.string(existing, "privilege_mode")), true, "SU_ROOT".equals(privilege) ? "root 비밀번호" : "sudo 인증 비밀번호");
        }
        if (create) {
            id = UUID.randomUUID().toString();
            store.jdbc.update("INSERT INTO scan_target (id, name, host, port, ssh_user, private_key, host_key, description, created_by, created_at, auth_type, ssh_password, key_passphrase, privilege_mode, privilege_password) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    id, input.name().strip(), input.host(), input.port(), input.sshUser(), key, hostKey, input.description(), actor, Instant.now().toString(), auth, password, passphrase, privilege, elevationPassword);
        } else {
            store.jdbc.update("UPDATE scan_target SET name = ?, host = ?, port = ?, ssh_user = ?, private_key = ?, host_key = ?, description = ?, auth_type = ?, ssh_password = ?, key_passphrase = ?, privilege_mode = ?, privilege_password = ? WHERE id = ?",
                    input.name().strip(), input.host(), input.port(), input.sshUser(), key, hostKey, input.description(), auth, password, passphrase, privilege, elevationPassword, id);
        }
        return id;
    }

    private String secret(String supplied, Map<String, Object> existing, String column, boolean preserve, boolean required, String label) {
        if (supplied != null && !supplied.isEmpty()) {
            if (supplied.getBytes(StandardCharsets.UTF_8).length > 1024 || supplied.codePoints().anyMatch(value -> value < 32 || value == 127)) {
                throw new ApiProblem(400, label + "는 제어 문자 없이 UTF-8 1,024바이트 이내로 입력하세요.");
            }
            return vault.encrypt(supplied);
        }
        String result = preserve ? Store.string(existing, column) : "";
        if (required && result.isEmpty()) { throw new ApiProblem(400, label + "가 필요합니다."); }
        return result;
    }

    public Map<String, Object> credentials(String id) { return store.one("SELECT * FROM scan_target WHERE id = ?", id); }
    public String decryptKey(Map<String, Object> target) { return decrypt(target, "private_key"); }
    public String decrypt(Map<String, Object> target, String field) {
        if (!Set.of("private_key", "ssh_password", "key_passphrase", "privilege_password").contains(field)) { throw new IllegalArgumentException("Unknown credential field"); }
        String encrypted = Store.string(target, field);
        return encrypted.isEmpty() ? "" : vault.decrypt(encrypted);
    }

    static void validateEndpoint(String host, int port) {
        if (host == null || !host.matches("[A-Za-z0-9][A-Za-z0-9.:-]{0,252}") || port < 1 || port > 65535) {
            throw new ApiProblem(400, "SSH 호스트·포트 형식을 확인하세요.");
        }
    }

    private void validate(Input input) {
        if (input.name() == null || input.name().isBlank() || input.name().length() > 120 || input.description() == null || input.description().length() > 1000) {
            throw new ApiProblem(400, "대상 이름(120자 이내)과 설명(1,000자 이내)을 확인하세요.");
        }
        validateEndpoint(input.host(), input.port());
        if (input.sshUser() == null || !input.sshUser().matches("[A-Za-z_][A-Za-z0-9_-]{0,63}")) {
            throw new ApiProblem(400, "SSH 계정 형식을 확인하세요.");
        }
    }

    public record Input(String name, String host, int port, String sshUser, String privateKey, String hostKey, String description,
                        String authType, String sshPassword, String keyPassphrase, String privilegeMode, String privilegePassword) {
        @Override public String toString() { return "TargetInput[credentials=REDACTED]"; }
    }
}
