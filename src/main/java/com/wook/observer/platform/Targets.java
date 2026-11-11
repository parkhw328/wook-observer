package com.wook.observer.platform;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
@Profile("!collector")
public class Targets {
    private final Store store;
    private final SecretVault vault;
    public Targets(Store store, SecretVault vault) { this.store = store; this.vault = vault; }

    public List<Map<String, Object>> list() {
        return store.rows("SELECT id, name, host, port, ssh_user, description, created_by, created_at FROM scan_target ORDER BY created_at DESC");
    }

    public synchronized String save(String id, Input input, String actor) {
        validate(input);
        boolean create = id == null;
        var existing = create ? null : credentials(id);
        if (create && (input.privateKey() == null || input.privateKey().isBlank())) { throw new ApiProblem(400, "SSH 개인 키가 필요합니다."); }
        String key = input.privateKey();
        if (key != null && !key.isBlank() && (key.length() > 32_000 || !key.startsWith("-----BEGIN ") || !key.contains("PRIVATE KEY-----"))) {
            throw new ApiProblem(400, "PEM/OpenSSH 형식의 암호 없는 SSH 개인 키를 입력하세요.");
        }
        String encrypted = key == null || key.isBlank() ? Store.string(existing, "private_key") : vault.encrypt(key.strip() + "\n");
        if (create) {
            id = UUID.randomUUID().toString();
            store.jdbc.update("INSERT INTO scan_target VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)", id, input.name().strip(),
                    input.host(), input.port(), input.sshUser(), encrypted, input.hostKey().strip(), input.description(), actor, Instant.now().toString());
        } else {
            store.jdbc.update("UPDATE scan_target SET name = ?, host = ?, port = ?, ssh_user = ?, private_key = ?, host_key = ?, description = ? WHERE id = ?",
                    input.name().strip(), input.host(), input.port(), input.sshUser(), encrypted, input.hostKey().strip(), input.description(), id);
        }
        return id;
    }

    public Map<String, Object> credentials(String id) { return store.one("SELECT * FROM scan_target WHERE id = ?", id); }
    public String decryptKey(Map<String, Object> target) { return vault.decrypt(Store.string(target, "private_key")); }

    private void validate(Input input) {
        if (input.name() == null || input.name().isBlank() || input.name().length() > 120 || input.description() == null || input.description().length() > 1000) {
            throw new ApiProblem(400, "대상 이름(120자 이내)과 설명(1,000자 이내)을 확인하세요.");
        }
        if (input.host() == null || !input.host().matches("[A-Za-z0-9][A-Za-z0-9.:-]{0,252}")
                || input.port() < 1 || input.port() > 65535 || input.sshUser() == null
                || !input.sshUser().matches("[A-Za-z_][A-Za-z0-9_-]{0,63}")) {
            throw new ApiProblem(400, "SSH 호스트·포트·계정 형식을 확인하세요.");
        }
        if (input.hostKey() == null || !input.hostKey().strip().matches("(?:ssh-ed25519|ssh-rsa|ecdsa-sha2-nistp(?:256|384|521)) [A-Za-z0-9+/=]{40,4096}")) {
            throw new ApiProblem(400, "서버에서 확인한 SSH 호스트 공개 키(유형 base64)를 입력하세요. 자동 신뢰는 허용하지 않습니다.");
        }
    }

    public record Input(String name, String host, int port, String sshUser, String privateKey, String hostKey, String description) { }
}
