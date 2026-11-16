package com.wook.observer.platform;

import com.wook.observer.ReportFixtures;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.List;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.Mockito.when;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.datasource.url=jdbc:h2:mem:platform;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE",
        "spring.datasource.username=sa", "spring.datasource.password=",
        "observer.platform.bootstrap-user=admin", "observer.platform.bootstrap-password=Init1234",
        "observer.platform.encryption-key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="})
class PlatformIntegrationTest {
    @Value("${local.server.port}") int port;
    @Autowired Store store;
    @Autowired Catalog catalog;
    @Autowired CatalogUpdates updates;
    @Autowired SecretVault vault;
    @Autowired Targets targets;
    @Autowired org.springframework.transaction.PlatformTransactionManager transactionManager;
    @MockitoBean SshCollector ssh;
    @TempDir static Path updateDirectory;
    private final JsonMapper json = JsonMapper.builder().build();

    @DynamicPropertySource
    static void updatePath(DynamicPropertyRegistry registry) { registry.add("observer.platform.update-directory", () -> updateDirectory.toString()); }

    @Test
    void operationalLogsTraceAsyncJobsAndExcludeCredentialsBodiesAndQueryStrings() throws Exception {
        try (var log = new OperationLogCapture(ch.qos.logback.classic.Level.INFO)) {
            Client admin = login("admin", "Init1234");
            log.events.clear();
            var read = admin.get("/api/targets?query=query-secret-canary");
            assertThat(read.headers().firstValue("X-Request-ID")).isPresent();
            assertThat(log.text()).doesNotContain("http.completed");
            log.logger.setLevel(ch.qos.logback.classic.Level.DEBUG);
            var request = HttpRequest.newBuilder(uri("/api/targets?query=query-secret-canary")).header("X-Request-ID", "untrusted-id-canary").GET().build();
            var response = admin.http.send(request, HttpResponse.BodyHandlers.ofString());
            String requestId = response.headers().firstValue("X-Request-ID").orElseThrow();
            assertThat(requestId).matches("[0-9a-f-]{36}").isNotEqualTo(read.headers().firstValue("X-Request-ID").orElseThrow());
            assertThat(log.events).anySatisfy(event -> {
                assertThat(event.getFormattedMessage()).contains("event=http.completed", "route=/api/targets", "status=200", "durationMs=");
                assertThat(event.getMDCPropertyMap()).containsEntry("requestId", requestId).containsEntry("actor", "admin").doesNotContainKey("jobId");
            });
            String secret = "ssh-password-canary";
            var created = admin.send("POST", "/api/admin/targets", Map.of("name", "logging-fixture", "host", "127.0.0.1", "port", 22,
                    "sshUser", "observer", "authType", "PASSWORD", "sshPassword", secret, "privilegeMode", "NONE",
                    "hostKey", "ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIAABAgMEBQYHCAkKCwwNDg8QERITFBUWFxgZGhscHR4f", "description", ""), true);
            assertThat(created.statusCode()).isEqualTo(200);
            String target = json.readTree(created.body()).path("id").asText();
            var profile = Map.of("assetCode", "", "environment", "TEST", "owner", "", "location", "", "purpose", "", "lifecycle", "ACTIVE", "notes", "notes-canary", "revision", 0);
            assertThat(admin.send("PUT", "/api/admin/assets/" + target, profile, true).statusCode()).isEqualTo(200);
            var service = new Assets.ServiceInput("test-service", "", "", "", "", "", "OTHER", "", "", "", "command-secret-canary", "", "", "", "", false, 0);
            assertThat(admin.send("POST", "/api/admin/assets/" + target + "/services", service, true).statusCode()).isEqualTo(200);
            when(ssh.collect(anyMap())).thenReturn(ReportFixtures.sample());
            var started = admin.send("POST", "/api/targets/" + target + "/scans", Map.of(), true);
            String job = json.readTree(started.body()).path("id").asText();
            String scanRequest = started.headers().firstValue("X-Request-ID").orElseThrow();
            awaitLog(log, "scan.completed", job);
            assertThat(log.events.stream().filter(e -> job.equals(e.getMDCPropertyMap().get("jobId"))).toList()).hasSizeGreaterThanOrEqualTo(5)
                    .allSatisfy(e -> assertThat(e.getMDCPropertyMap()).containsEntry("requestId", scanRequest).containsEntry("actor", "admin").containsEntry("targetId", target));
            assertThat(admin.binary("/api/assets/" + target + "/export.xlsx").statusCode()).isEqualTo(200);
            assertThat(admin.binary("/api/jobs/" + job + "/download/json").statusCode()).isEqualTo(200);
            when(ssh.collect(anyMap())).thenThrow(new IllegalStateException("exception-secret-canary"));
            var failed = admin.send("POST", "/api/targets/" + target + "/scans", Map.of(), true);
            String failedJob = json.readTree(failed.body()).path("id").asText();
            awaitLog(log, "scan.failed", failedJob);
            assertThat(log.events).anySatisfy(event -> {
                assertThat(event.getFormattedMessage()).contains("event=scan.failed", "phase=COLLECTION", "durationMs=", "errorType=IllegalStateException");
                assertThat(event.getMDCPropertyMap()).containsEntry("jobId", failedJob).containsEntry("requestId", failed.headers().firstValue("X-Request-ID").orElseThrow());
            });
            var invalid = HttpRequest.newBuilder(uri("/api/admin/targets")).header("X-CSRF-TOKEN", admin.csrf).header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString("{\"sshPassword\":\"malformed-secret-canary\",invalid}")).build();
            assertThat(admin.http.send(invalid, HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(400);
            assertThat(new Client().get("/api/targets").statusCode()).isEqualTo(401);
            assertThat(admin.send("POST", "/api/admin/targets", Map.of("sshPassword", secret), false).statusCode()).isEqualTo(403);
            Client denied = new Client(); denied.refresh();
            var badLogin = HttpRequest.newBuilder(uri("/api/login")).header("X-CSRF-TOKEN", denied.csrf).header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.ofString("username=admin&password=login-secret-canary")).build();
            assertThat(denied.http.send(badLogin, HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(401);
            assertThat(log.text()).contains("asset.profile.saved", "asset.service.saved", "asset.exported", "report.downloaded", "api.invalid_input", "status=401", "status=403")
                    .contains("auth.login.failed", "reason=BadCredentialsException")
                    .doesNotContain(secret, "Init1234", "query-secret-canary", "untrusted-id-canary", "notes-canary", "command-secret-canary", "exception-secret-canary", "malformed-secret-canary", "login-secret-canary");
        }
    }

    @Test
    void committedChangesLogOnceAndRolledBackChangesNeverClaimSuccess() {
        var tx = new org.springframework.transaction.support.TransactionTemplate(transactionManager);
        try (var log = new OperationLogCapture(ch.qos.logback.classic.Level.INFO)) {
            tx.executeWithoutResult(status -> { OperationLog.afterCommit("test.saved", "operation", "rollback"); status.setRollbackOnly(); });
            assertThat(log.text()).doesNotContain("test.saved");
            tx.executeWithoutResult(status -> {
                OperationLog.afterCommit("test.saved", "operation", "commit");
                assertThat(log.text()).doesNotContain("test.saved");
            });
            assertThat(log.events).hasSize(1);
            assertThat(log.text()).contains("event=test.saved operation=commit");
        }
    }

    private void awaitLog(OperationLogCapture log, String event, String job) throws InterruptedException {
        for (int i = 0; i < 200; i++) {
            if (log.events.stream().anyMatch(e -> e.getFormattedMessage().contains("event=" + event + " ") && job.equals(e.getMDCPropertyMap().get("jobId")))) return;
            Thread.sleep(25);
        }
        fail("Missing operational event " + event);
    }

    @Test
    void authenticationCsrfRolesAndSessionRevocationAreEnforced() throws Exception {
        Client anonymous = new Client();
        assertThat(anonymous.get("/api/targets").statusCode()).isEqualTo(401);
        assertThat(anonymous.get("/actuator/health").statusCode()).isEqualTo(200);
        assertThat(anonymous.get("/observer-mark.svg").statusCode()).isEqualTo(200);
        assertThat(anonymous.get("/icons.svg").statusCode()).isEqualTo(200);
        Client admin = login("admin", "Init1234");
        assertThat(admin.send("POST", "/api/admin/users", Map.of("username", "denied", "password", "a-password-1234", "role", "USER"), false).statusCode()).isEqualTo(403);
        assertThat(admin.send("POST", "/api/admin/users", Map.of("username", "reader", "password", "reader-password-123", "role", "USER"), true).statusCode()).isEqualTo(200);
        Client reader = login("reader", "reader-password-123");
        assertThat(reader.get("/api/admin/users").statusCode()).isEqualTo(403);
        assertThat(reader.get("/api/targets").statusCode()).isEqualTo(200);
        assertThat(reader.send("POST", "/api/admin/targets", Map.of(), true).statusCode()).isEqualTo(403);
        assertThat(admin.send("PUT", "/api/admin/users/admin", Map.of("role", "USER", "enabled", false), true).statusCode()).isEqualTo(409);
        assertThat(admin.send("PUT", "/api/admin/users/reader", Map.of("role", "USER", "enabled", false), true).statusCode()).isEqualTo(200);
        assertThat(reader.get("/api/targets").statusCode()).isEqualTo(401);
        assertThat(admin.send("POST", "/api/v1/observations", Map.of(), true).statusCode()).isEqualTo(404);
    }

    @Test
    void acceptsEightCharacterPasswordsAndKeepsTheUtf8ByteLimit() throws Exception {
        // The test application's bootstrap administrator also uses an eight-character password.
        Client admin = login("admin", "Init1234");
        for (String invalid : new String[]{"1234567", "x".repeat(73), "가".repeat(25), ""}) {
            var response = admin.send("POST", "/api/admin/users", Map.of("username", "too-short-or-long",
                    "password", invalid, "role", "USER"), true);
            assertThat(response.statusCode()).isEqualTo(400);
            assertThat(response.body()).contains("8자 이상", "72바이트");
        }
        assertThat(store.rows("SELECT username FROM app_user WHERE username = ?", "too-short-or-long")).isEmpty();
        assertThat(admin.send("POST", "/api/admin/users", Map.of("username", "eight-character-user", "password", "Eight123", "role", "USER"), true)
                .statusCode()).isEqualTo(200);
        assertThat(login("eight-character-user", "Eight123").get("/api/targets").statusCode()).isEqualTo(200);
        assertThat(admin.send("POST", "/api/admin/users", Map.of("username", "max-byte-user", "password", "x".repeat(72), "role", "USER"), true)
                .statusCode()).isEqualTo(200);
        assertThat(login("max-byte-user", "x".repeat(72)).get("/api/targets").statusCode()).isEqualTo(200);
    }

    @Test
    void assetManagementRequiresAdminCsrfAndPersistsTheWorkbookFields() throws Exception {
        Client anonymous = new Client();
        assertThat(anonymous.get("/api/assets").statusCode()).isEqualTo(401);
        Client admin = login("admin", "Init1234");
        admin.send("POST", "/api/admin/users", Map.of("username", "asset-reader", "password", "Reader123", "role", "USER"), true);
        Client reader = login("asset-reader", "Reader123");
        String target = targets.save(null, new Targets.Input("asset-access", "192.0.2.10", 22, "observer", "",
                "ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIAABAgMEBQYHCAkKCwwNDg8QERITFBUWFxgZGhscHR4f", "fixture", "PASSWORD", "secret-fixture", "", "NONE", ""), "admin");
        var profile = new Assets.ProfileInput("INV-01", "PRODUCTION", "운영팀", "IDC", "업무", "ACTIVE", "memo", 0);
        assertThat(reader.get("/api/assets/" + target).statusCode()).isEqualTo(200);
        assertThat(reader.send("PUT", "/api/admin/assets/" + target, profile, true).statusCode()).isEqualTo(403);
        assertThat(admin.send("PUT", "/api/admin/assets/" + target, profile, false).statusCode()).isEqualTo(403);
        assertThat(admin.send("PUT", "/api/admin/assets/" + target, profile, true).statusCode()).isEqualTo(200);
        assertThat(admin.send("PUT", "/api/admin/assets/" + target, profile, true).statusCode()).isEqualTo(409);
        var service = new Assets.ServiceInput("업무 서비스", "TCP 8443", "업무파트", "JEUS", "8.5", "appuser", "APPLICATION", "/APP/example", "/DATA/example", "/LOG/example",
                "start.sh", "stop.sh", "status.sh", "내부 → 외부", "<script>alert(1)</script>", false, 0);
        String endpoint = "/api/admin/assets/" + target + "/services";
        assertThat(reader.send("POST", endpoint, service, true).statusCode()).isEqualTo(403);
        assertThat(admin.send("POST", endpoint, service, false).statusCode()).isEqualTo(403);
        var created = admin.send("POST", endpoint, service, true);
        assertThat(created.statusCode()).isEqualTo(200);
        String id = json.readTree(created.body()).path("id").asText();
        assertThat(reader.send("PUT", endpoint + "/" + id, service, true).statusCode()).isEqualTo(403);
        assertThat(admin.send("PUT", endpoint + "/" + id, service, true).statusCode()).isEqualTo(409);
        var detail = reader.get("/api/assets/" + target);
        assertThat(detail.body()).contains("INV-01", "status.sh", "/DATA/example", "JEUS").doesNotContain("secret-fixture", "ssh_password", "private_key", "host_key");
        assertThat(json.readTree(detail.body()).path("services").get(0).path("observation_status").asText()).isEqualTo("MANUAL");
        assertThat(admin.get("/api/assets/nonexistent").statusCode()).isEqualTo(404);
        assertThat(anonymous.binary("/api/assets/export.xlsx").statusCode()).isEqualTo(401);
        assertThat(anonymous.binary("/api/assets/" + target + "/export.xlsx").statusCode()).isEqualTo(401);
        assertThat(reader.binary("/api/assets/nonexistent/export.xlsx").statusCode()).isEqualTo(404);
        for (String path : List.of("/api/assets/export.xlsx", "/api/assets/" + target + "/export.xlsx")) {
            var exported = reader.binary(path);
            assertThat(exported.statusCode()).isEqualTo(200);
            assertThat(exported.headers().firstValue("Cache-Control")).contains("no-store");
            assertThat(exported.headers().firstValue("Content-Disposition").orElseThrow()).contains("attachment", ".xlsx");
            try (var workbook = new org.apache.poi.xssf.usermodel.XSSFWorkbook(new java.io.ByteArrayInputStream(exported.body()))) {
                var rows = workbook.getSheet("자산 목록");
                boolean found = false;
                for (var row : rows) if (row.getRowNum() > 0 && row.getCell(22).getStringCellValue().equals(target)) found = true;
                assertThat(found).isTrue();
                if (path.contains(target)) {
                    assertThat(rows.getLastRowNum()).isEqualTo(1);
                    assertThat(workbook.getSheet("서비스 운영대장").getRow(1).getCell(16).getStringCellValue()).isEqualTo("status.sh");
                }
                for (var sheet : workbook) for (var row : sheet) for (var cell : row) assertThat(cell.toString()).doesNotContain("secret-fixture");
            }
        }
    }

    @Test
    void resetsToEightCharactersRevokesSessionsAndPreservesBlankPasswordEdits() throws Exception {
        Client admin = login("admin", "Init1234");
        assertThat(admin.send("POST", "/api/admin/users", Map.of("username", "reset-boundary-user", "password", "Initial-password-123", "role", "USER"), true)
                .statusCode()).isEqualTo(200);
        Client existing = login("reset-boundary-user", "Initial-password-123");
        var before = store.one("SELECT password_hash, revision FROM app_user WHERE username = ?", "reset-boundary-user");
        assertThat(admin.send("PUT", "/api/admin/users/reset-boundary-user", Map.of("password", "1234567", "role", "USER", "enabled", true), true)
                .statusCode()).isEqualTo(400);
        assertThat(store.one("SELECT password_hash, revision FROM app_user WHERE username = ?", "reset-boundary-user")).isEqualTo(before);
        assertThat(existing.get("/api/targets").statusCode()).isEqualTo(200);
        assertThat(admin.send("PUT", "/api/admin/users/reset-boundary-user", Map.of("password", "Reset123", "role", "USER", "enabled", true), true)
                .statusCode()).isEqualTo(200);
        assertThat(existing.get("/api/targets").statusCode()).isEqualTo(401);
        assertThat(login("reset-boundary-user", "Reset123").get("/api/targets").statusCode()).isEqualTo(200);
        String hash = Store.string(store.one("SELECT password_hash FROM app_user WHERE username = ?", "reset-boundary-user"), "password_hash");
        assertThat(admin.send("PUT", "/api/admin/users/reset-boundary-user", Map.of("password", "", "role", "USER", "enabled", true), true)
                .statusCode()).isEqualTo(200);
        assertThat(Store.string(store.one("SELECT password_hash FROM app_user WHERE username = ?", "reset-boundary-user"), "password_hash")).isEqualTo(hash);
        assertThat(login("reset-boundary-user", "Reset123").get("/api/targets").statusCode()).isEqualTo(200);
    }

    @Test
    void registeredTargetProducesPersistedAssessmentAndAllDownloads() throws Exception {
        when(ssh.collect(anyMap())).thenReturn(ReportFixtures.sample());
        Client admin = login("admin", "Init1234");
        String secret = "-----BEGIN OPENSSH PRIVATE KEY-----\nTEST-ONLY-PRIVATE-CONTENT\n-----END OPENSSH PRIVATE KEY-----";
        var response = admin.send("POST", "/api/admin/targets", Map.of("name", "fixture-target", "host", "127.0.0.1", "port", 22,
                "sshUser", "observer", "privateKey", secret, "hostKey", "ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIAABAgMEBQYHCAkKCwwNDg8QERITFBUWFxgZGhscHR4f", "description", "test"), true);
        assertThat(response.statusCode()).isEqualTo(200);
        String target = json.readTree(response.body()).path("id").asText();
        assertThat(admin.get("/api/targets").body()).doesNotContain("PRIVATE", "private_key");
        assertThat(admin.get("/api/admin/targets/"+target).body()).doesNotContain("PRIVATE", "private_key");
        String encrypted = Store.string(store.one("SELECT private_key FROM scan_target WHERE id = ?", target), "private_key");
        assertThat(encrypted).doesNotContain("PRIVATE");
        assertThat(vault.decrypt(encrypted)).contains("TEST-ONLY-PRIVATE-CONTENT");
        var started = admin.send("POST", "/api/targets/"+target+"/scans", Map.of(), true);
        assertThat(started.statusCode()).isEqualTo(202);
        String job = json.readTree(started.body()).path("id").asText();
        HttpResponse<String> report = admin.get("/api/jobs/"+job);
        for (int i=0; i<100 && report.statusCode()==409; i++) { Thread.sleep(30); report=admin.get("/api/jobs/"+job); }
        assertThat(report.statusCode()).isEqualTo(200);
        assertThat(json.readTree(admin.get("/api/assets/" + target).body()).path("services").size()).isEqualTo(1);
        assertThat(report.body()).contains("assessment", "catalogRevision", "미판정");
        var operator = json.readTree(admin.get("/api/jobs/"+job+"/overview").body());
        assertThat(operator.path("operator").path("actions").isArray()).isTrue();
        assertThat(operator.path("report").path("assessment").path("catalogRevision").asInt()).isPositive();
        for (String format : new String[]{"xlsx","json","md","zip"}) {
            var downloaded = admin.binary("/api/jobs/"+job+"/download/"+format);
            assertThat(downloaded.statusCode()).isEqualTo(200);
            assertThat(downloaded.headers().firstValue("Cache-Control")).contains("no-store");
            if (format.equals("xlsx")) {
                try (var workbook = new org.apache.poi.xssf.usermodel.XSSFWorkbook(new java.io.ByteArrayInputStream(downloaded.body()))) {
                    assertThat(workbook.getNumberOfSheets()).isEqualTo(35);
                    assertThat(workbook.getSheet("취약점 상세")).isNotNull();
                    assertThat(workbook.getSheet("애플리케이션_오픈소스").getRow(2).getCell(2).getStringCellValue()).isEqualTo("취약점발견");
                }
            }
        }
    }

    @Test
    void passwordAndRootSecretsAreEncryptedHiddenPreservedAndClearedOnModeSwitch() throws Exception {
        Client admin = login("admin", "Init1234");
        var input = new java.util.LinkedHashMap<String, Object>(Map.of("name", "password-target", "host", "127.0.0.1", "port", 22,
                "sshUser", "operator", "hostKey", "ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIAABAgMEBQYHCAkKCwwNDg8QERITFBUWFxgZGhscHR4f comment@host", "description", "auth test"));
        input.putAll(Map.of("authType", "PASSWORD", "sshPassword", "login-secret-$'`!", "privilegeMode", "SU_ROOT", "privilegePassword", "root-secret-$'`!"));
        var response = admin.send("POST", "/api/admin/targets", input, true);
        assertThat(response.statusCode()).isEqualTo(200);
        String id = json.readTree(response.body()).path("id").asText();
        var stored = targets.credentials(id);
        assertThat(Store.string(stored, "ssh_password")).doesNotContain("login-secret");
        assertThat(Store.string(stored, "privilege_password")).doesNotContain("root-secret");
        assertThat(targets.decrypt(stored, "ssh_password")).isEqualTo("login-secret-$'`!");
        assertThat(targets.decrypt(stored, "privilege_password")).isEqualTo("root-secret-$'`!");
        var safe = admin.get("/api/admin/targets/" + id);
        assertThat(safe.body()).doesNotContain("login-secret", "root-secret", "ssh_password", "privilege_password", "private_key", "key_passphrase",
                Store.string(stored, "ssh_password"), Store.string(stored, "privilege_password"));
        assertThat(json.readTree(safe.body()).path("hasSshPassword").asBoolean()).isTrue();
        assertThat(json.readTree(safe.body()).path("hasPrivateKey").asBoolean()).isFalse();
        assertThat(json.readTree(safe.body()).path("hostKeyFingerprint").asText())
                .isEqualTo(HostKeys.describe(input.get("hostKey").toString()).fingerprint());
        assertThat(admin.get("/api/targets").body()).doesNotContain("login-secret", "root-secret", "ssh_password", "privilege_password");
        input.put("sshPassword", ""); input.put("privilegePassword", "");
        assertThat(admin.send("PUT", "/api/admin/targets/" + id, input, true).statusCode()).isEqualTo(200);
        assertThat(targets.credentials(id).get("ssh_password")).isEqualTo(stored.get("ssh_password"));
        input.put("privilegeMode", "SUDO");
        assertThat(admin.send("PUT", "/api/admin/targets/" + id, input, true).statusCode()).isEqualTo(400);
        input.put("privilegeMode", "NONE"); input.put("authType", "KEY");
        assertThat(admin.send("PUT", "/api/admin/targets/" + id, input, true).statusCode()).isEqualTo(400);
        input.put("privateKey", "-----BEGIN OPENSSH PRIVATE KEY-----\nTEST-PRIVATE\n-----END OPENSSH PRIVATE KEY-----");
        input.put("keyPassphrase", "key-passphrase-test");
        assertThat(admin.send("PUT", "/api/admin/targets/" + id, input, true).statusCode()).isEqualTo(200);
        var switched = targets.credentials(id);
        assertThat(Store.string(switched, "ssh_password")).isEmpty();
        assertThat(Store.string(switched, "privilege_password")).isEmpty();
        assertThat(targets.decrypt(switched, "key_passphrase")).isEqualTo("key-passphrase-test");
        assertThat(admin.get("/api/admin/targets/" + id).body()).doesNotContain("key-passphrase-test", "TEST-PRIVATE");
        input.put("authType", "PASSWORD"); input.put("sshPassword", "line1\nline2");
        assertThat(admin.send("PUT", "/api/admin/targets/" + id, input, true).statusCode()).isEqualTo(400);
        assertThat(targets.credentials(id).get("auth_type")).isEqualTo("KEY");
    }

    @Test
    void hostProbeIsAdminOnlyCsrfProtectedAndRejectsCommandArguments() throws Exception {
        Client anonymous = new Client();
        anonymous.refresh();
        assertThat(anonymous.send("POST", "/api/admin/ssh/host-keys", Map.of("host", "127.0.0.1", "port", 22), true).statusCode()).isEqualTo(401);
        Client admin = login("admin", "Init1234");
        assertThat(admin.send("POST", "/api/admin/ssh/host-keys", Map.of("host", "127.0.0.1", "port", 22), false).statusCode()).isEqualTo(403);
        assertThat(admin.send("POST", "/api/admin/ssh/host-keys", Map.of("host", "-f /etc/passwd", "port", 22), true).statusCode()).isEqualTo(400);
        assertThat(anonymous.binary("/fonts/JetBrainsMono-Regular.woff2").statusCode()).isEqualTo(200);
        assertThat(anonymous.binary("/fonts/NotoSansKR-Variable.ttf").statusCode()).isEqualTo(200);
    }

    @Test
    void pagesCatalogWithStableOrderingAccurateTotalsAndLiteralSearch() throws Exception {
        var records = new java.util.ArrayList<JsonNode>();
        for (int i = 0; i < 100; i++) {
            records.add(json.readTree(advisory("PAGE-test-" + String.format("%03d", i), "2026-11-11T00:00:00Z", false)));
        }
        catalog.importBytes(json.writeValueAsBytes(records), "pagination-test");
        Client client = login("admin", "Init1234");
        var first = json.readTree(client.get("/api/catalog/page?query=PAGE-test-&page=0").body());
        var second = json.readTree(client.get("/api/catalog/page?query=PAGE-test-&page=1").body());
        assertThat(first.path("total").asInt()).isEqualTo(100);
        assertThat(first.path("totalPages").asInt()).isEqualTo(2);
        assertThat(first.path("hasPrevious").asBoolean()).isFalse();
        assertThat(first.path("hasNext").asBoolean()).isTrue();
        assertThat(second.path("hasNext").asBoolean()).isFalse();
        assertThat(second.path("items").get(0).path("advisory_id").asText()).isEqualTo("PAGE-test-050");
        var ids = new java.util.HashSet<String>();
        first.path("items").forEach(r -> ids.add(r.path("advisory_id").asText()));
        second.path("items").forEach(r -> ids.add(r.path("advisory_id").asText()));
        assertThat(ids).hasSize(100);
        assertThat(catalog.page("PAGE-test-", 99).page()).isEqualTo(1);
        assertThat(catalog.page("PAGE-test-099", 1).items()).hasSize(1);
        assertThat(catalog.page("PAGE-test-%", 0).total()).isZero();
        assertThat(catalog.page("PAGE-test-_", 0).total()).isZero();
        assertThat(catalog.page("nonexistent-page-record", 0).hasNext()).isFalse();
        assertThat(client.get("/api/catalog/page?page=-1").statusCode()).isEqualTo(400);
        assertThat(json.readTree(client.get("/api/catalog?query=PAGE-test-&page=1").body()).isArray()).isTrue();
    }

    @Test
    void streamsTargetCatalogWithoutChangingOriginalDocumentsOrAssessment() throws Exception {
        byte[] originals = getClass().getResourceAsStream("/catalog/rpm-advisories.json").readAllBytes();
        catalog.importBytes(originals, "rpm-selection-test");
        var base = com.wook.observer.ReportFixtures.sample();
        var rpm = new com.wook.observer.model.SystemInventory.RpmPackage("openssl-libs", "1", "3.0.1", "42.el9_0", "x86_64",
                "openssl-3.0.1-42.el9_0.src.rpm", "", "Rocky Enterprise Software Foundation");
        var inventory = new com.wook.observer.model.SystemInventory(List.of(),
                new com.wook.observer.model.SystemInventory.RpmInventory(com.wook.observer.model.SystemInventory.Status.COLLECTED, "", List.of(rpm)));
        var report = new com.wook.observer.model.Observation(base.version(), base.collectedAt(), base.host(), base.processes(), base.javaProcesses(),
                base.containers(), base.issues(), Map.of("host:os.id", "rocky", "host:os.version", "9.0"), null, inventory);
        var full = catalog.snapshot();
        var selected = catalog.snapshot(report);
        assertThat(selected.revision()).isEqualTo(full.revision());
        assertThat(selected.records()).noneMatch(r -> r.path("id").asText().startsWith("RHSA-"));
        assertThat(selected.records()).anyMatch(r -> r.path("id").asText().equals("RLSA-2022:7288"));
        var assessor = new Assessor(null);
        var expected = assessor.assess(report, full).assessment();
        var actual = assessor.assess(report, selected).assessment();
        assertThat(actual.findings()).containsExactlyInAnyOrderElementsOf(expected.findings());
        assertThat(actual.coverage()).containsExactlyInAnyOrderElementsOf(expected.coverage());
        assertThat(catalog.snapshot().records()).containsAll(json.readTree(originals).valueStream().toList());
    }

    @Test
    void catalogImportsAtomicallyRejectsRollbackAndRecordsFailures() {
        assertThat(catalog.search("CVE-2021-44228", 0)).isNotEmpty();
        int before = store.jdbc.queryForObject("SELECT COUNT(*) FROM catalog_entry", Integer.class);
        byte[] invalid = ("[" + advisory("TEST-atomic", "2026-11-11T00:00:00Z", false) + ",{\"id\":\"broken\"}]").getBytes(StandardCharsets.UTF_8);
        assertThatThrownBy(() -> catalog.importBytes(invalid, "invalid-test")).isInstanceOf(ApiProblem.class);
        assertThat(store.jdbc.queryForObject("SELECT COUNT(*) FROM catalog_entry", Integer.class)).isEqualTo(before);
        catalog.importBytes(advisory("TEST-withdraw", "2026-11-11T00:00:00Z", false).getBytes(StandardCharsets.UTF_8), "test");
        catalog.importBytes(advisory("TEST-withdraw", "2026-11-12T00:00:00Z", true).getBytes(StandardCharsets.UTF_8), "test");
        catalog.importBytes(advisory("TEST-withdraw", "2026-11-10T00:00:00Z", false).getBytes(StandardCharsets.UTF_8), "old");
        assertThat(catalog.snapshot().records().stream().filter(r -> "TEST-withdraw".equals(r.path("id").asText())).findFirst().orElseThrow().hasNonNull("withdrawn")).isTrue();
        assertThat(catalog.search("TEST-withdraw", 0)).hasSize(1);
        assertThat(updates.history().stream().anyMatch(r -> "FAILED".equals(r.get("status")))).isTrue();
    }

    @Test
    void rejectsDirectoryPayloadsAndOversizedCompressedEntriesWithoutChangingCatalog() throws Exception {
        int revision = ((Number) catalog.status().get("revision")).intValue();
        for (String name : new String[]{"records/", "oversized.json"}) {
            var bytes = new java.io.ByteArrayOutputStream();
            try (var zip = new java.util.zip.ZipOutputStream(bytes)) {
                zip.putNextEntry(new java.util.zip.ZipEntry(name));
                zip.write(name.endsWith("/") ? new byte[]{1} : new byte[4 * 1024 * 1024 + 1]);
                zip.closeEntry();
            }
            assertThatThrownBy(() -> catalog.importBytes(bytes.toByteArray(), "invalid-zip-test")).isInstanceOf(ApiProblem.class);
            assertThat(((Number) catalog.status().get("revision")).intValue()).isEqualTo(revision);
        }
    }

    @Test
    void offlineScheduledUpdateRunsAndRejectsUnapprovedSources() throws Exception {
        Files.writeString(updateDirectory.resolve("test.json"), advisory("TEST-scheduled", "2026-11-11T00:00:00Z", false));
        updates.configure(new CatalogUpdates.Settings(true,"0 0 3 * * *","Asia/Seoul","test.json"));
        store.jdbc.update("UPDATE update_schedule SET next_run = '2000-01-01T00:00:00Z' WHERE singleton = 1");
        updates.scheduledUpdate();
        assertThat(catalog.search("TEST-scheduled", 0)).hasSize(1);
        assertThat(Store.string(updates.settings(), "next_run")).doesNotStartWith("2000-");
        assertThatThrownBy(() -> updates.configure(new CatalogUpdates.Settings(true,"invalid","Asia/Seoul","test.json"))).isInstanceOf(ApiProblem.class);
        assertThatThrownBy(() -> updates.configure(new CatalogUpdates.Settings(true,"0 0 3 * * *","Asia/Seoul","../test.json"))).isInstanceOf(ApiProblem.class);
        assertThatThrownBy(() -> updates.configure(new CatalogUpdates.Settings(true,"0 0 3 * * *","Asia/Seoul","https://example.com/catalog.json"))).isInstanceOf(ApiProblem.class);
        updates.configure(new CatalogUpdates.Settings(false,"0 0 3 * * *","Asia/Seoul","missing.json"));
        assertThatThrownBy(updates::runNow).isInstanceOf(ApiProblem.class);
    }

    private String advisory(String id, String modified, boolean withdrawn) {
        return "{\"id\":\""+id+"\",\"modified\":\""+modified+"\","
                +(withdrawn?"\"withdrawn\":\""+modified+"\",":"")
                +"\"affected\":[{\"package\":{\"ecosystem\":\"Maven\",\"name\":\"test:example\"},\"ranges\":[{\"type\":\"ECOSYSTEM\",\"events\":[{\"introduced\":\"0\"},{\"fixed\":\"2\"}]}]}]}";
    }

    private Client login(String username, String password) throws Exception {
        Client client = new Client();
        client.refresh();
        var request = HttpRequest.newBuilder(uri("/api/login")).header("X-CSRF-TOKEN", client.csrf)
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString("username="+username+"&password="+password)).build();
        assertThat(client.http.send(request, HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(204);
        client.refresh();
        return client;
    }
    private URI uri(String path) { return URI.create("http://127.0.0.1:"+port+path); }
    private class Client {
        final HttpClient http = HttpClient.newBuilder().cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL)).build();
        String csrf;
        void refresh() throws Exception { csrf=json.readTree(get("/api/session").body()).path("csrf").asText(); }
        HttpResponse<String> get(String path) throws Exception { return http.send(HttpRequest.newBuilder(uri(path)).GET().build(), HttpResponse.BodyHandlers.ofString()); }
        HttpResponse<byte[]> binary(String path) throws Exception { return http.send(HttpRequest.newBuilder(uri(path)).GET().build(), HttpResponse.BodyHandlers.ofByteArray()); }
        HttpResponse<String> send(String method, String path, Object body, boolean token) throws Exception {
            var builder=HttpRequest.newBuilder(uri(path)).header("Content-Type","application/json");
            if(token) builder.header("X-CSRF-TOKEN",csrf);
            return http.send(builder.method(method,HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))).build(),HttpResponse.BodyHandlers.ofString());
        }
    }
}
