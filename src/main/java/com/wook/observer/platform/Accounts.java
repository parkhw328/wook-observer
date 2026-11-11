package com.wook.observer.platform;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;

@Service
@Profile("!collector")
public class Accounts implements UserDetailsService, ApplicationRunner {
    private final Store store;
    private final TransactionTemplate transactions;
    private final BCryptPasswordEncoder passwords = new BCryptPasswordEncoder(12);
    private final String bootstrapUser;
    private final String bootstrapPassword;

    public Accounts(Store store, TransactionTemplate transactions,
                    @Value("${observer.platform.bootstrap-user}") String user,
                    @Value("${observer.platform.bootstrap-password}") String password) {
        this.store = store; this.transactions = transactions;
        bootstrapUser = user; bootstrapPassword = password;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (store.jdbc.queryForObject("SELECT COUNT(*) FROM app_user", Integer.class) == 0) {
            create(bootstrapUser, bootstrapPassword, "ADMIN");
        }
    }

    @Override
    public UserDetails loadUserByUsername(String username) {
        var rows = store.rows("SELECT * FROM app_user WHERE username = ?", username);
        if (rows.isEmpty()) { throw new UsernameNotFoundException("Unknown account"); }
        var row = rows.getFirst();
        return new Account(username, Store.string(row, "password_hash"), Store.string(row, "role"),
                Boolean.TRUE.equals(row.get("enabled")), ((Number) row.get("revision")).intValue());
    }

    public List<Map<String, Object>> list() {
        return store.rows("SELECT username, role, enabled, created_at FROM app_user ORDER BY username");
    }

    public synchronized void create(String username, String password, String role) {
        create(username, password, role, true);
    }

    public synchronized void create(String username, String password, String role, boolean enabled) {
        if (username == null || !username.matches("[A-Za-z0-9][A-Za-z0-9._-]{2,63}")) {
            throw new ApiProblem(400, "아이디는 영문·숫자·._- 3~64자로 입력하세요.");
        }
        validateRole(role); validatePassword(password);
        if (!store.rows("SELECT username FROM app_user WHERE username = ?", username).isEmpty()) {
            throw new ApiProblem(409, "이미 존재하는 사용자입니다.");
        }
        store.jdbc.update("INSERT INTO app_user(username, password_hash, role, enabled, created_at) VALUES (?, ?, ?, ?, ?)",
                username, passwords.encode(password), role, enabled, Instant.now().toString());
    }

    public synchronized void update(String username, String password, String role, boolean enabled) {
        validateRole(role);
        if (password != null && !password.isBlank()) { validatePassword(password); }
        transactions.executeWithoutResult(status -> {
            var existing = store.one("SELECT * FROM app_user WHERE username = ? FOR UPDATE", username);
            if ("ADMIN".equals(existing.get("role")) && Boolean.TRUE.equals(existing.get("enabled"))
                    && (!enabled || !"ADMIN".equals(role))
                    && store.jdbc.queryForObject("SELECT COUNT(*) FROM app_user WHERE role = 'ADMIN' AND enabled = TRUE", Integer.class) <= 1) {
                throw new ApiProblem(409, "마지막 활성 관리자는 비활성화하거나 권한을 낮출 수 없습니다.");
            }
            String hash = password == null || password.isBlank() ? Store.string(existing, "password_hash") : passwords.encode(password);
            store.jdbc.update("UPDATE app_user SET password_hash = ?, role = ?, enabled = ?, revision = revision + 1 WHERE username = ?",
                    hash, role, enabled, username);
        });
    }

    private void validateRole(String role) {
        if (!List.of("ADMIN", "USER").contains(role == null ? "" : role)) { throw new ApiProblem(400, "권한은 ADMIN 또는 USER입니다."); }
    }

    private void validatePassword(String password) {
        if (password == null || password.length() < 12 || password.getBytes(StandardCharsets.UTF_8).length > 72) {
            throw new ApiProblem(400, "비밀번호는 12자 이상, UTF-8 72바이트 이하여야 합니다.");
        }
    }

    public record Account(String username, String password, String role, boolean enabled, int revision) implements UserDetails {
        @Override public Collection<? extends GrantedAuthority> getAuthorities() { return List.of(new SimpleGrantedAuthority("ROLE_" + role)); }
        @Override public String getPassword() { return password; }
        @Override public String getUsername() { return username; }
        @Override public boolean isEnabled() { return enabled; }
    }
}
