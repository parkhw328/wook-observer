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

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.Mockito.when;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.datasource.url=jdbc:h2:mem:platform;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE",
        "spring.datasource.username=sa", "spring.datasource.password=",
        "observer.platform.bootstrap-user=admin", "observer.platform.bootstrap-password=Integration-password-123",
        "observer.platform.encryption-key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="})
class PlatformIntegrationTest {
    @Value("${local.server.port}") int port;
    @Autowired Store store;
    @Autowired Catalog catalog;
    @Autowired CatalogUpdates updates;
    @Autowired SecretVault vault;
    @MockitoBean SshCollector ssh;
    @TempDir static Path updateDirectory;
    private final JsonMapper json = JsonMapper.builder().build();

    @DynamicPropertySource
    static void updatePath(DynamicPropertyRegistry registry) { registry.add("observer.platform.update-directory", () -> updateDirectory.toString()); }

    @Test
    void authenticationCsrfRolesAndSessionRevocationAreEnforced() throws Exception {
        Client anonymous = new Client();
        assertThat(anonymous.get("/api/targets").statusCode()).isEqualTo(401);
        assertThat(anonymous.get("/actuator/health").statusCode()).isEqualTo(200);
        Client admin = login("admin", "Integration-password-123");
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
    void registeredTargetProducesPersistedAssessmentAndAllDownloads() throws Exception {
        when(ssh.collect(anyMap())).thenReturn(ReportFixtures.sample());
        Client admin = login("admin", "Integration-password-123");
        String secret = "-----BEGIN OPENSSH PRIVATE KEY-----\nTEST-ONLY-PRIVATE-CONTENT\n-----END OPENSSH PRIVATE KEY-----";
        var response = admin.send("POST", "/api/admin/targets", Map.of("name", "fixture-target", "host", "127.0.0.1", "port", 22,
                "sshUser", "observer", "privateKey", secret, "hostKey", "ssh-ed25519 " + "A".repeat(64), "description", "test"), true);
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
        assertThat(report.body()).contains("assessment", "catalogRevision", "점검 불가");
        for (String format : new String[]{"xlsx","json","md","zip"}) {
            var downloaded = admin.binary("/api/jobs/"+job+"/download/"+format);
            assertThat(downloaded.statusCode()).isEqualTo(200);
            assertThat(downloaded.headers().firstValue("Cache-Control")).contains("no-store");
            if (format.equals("xlsx")) {
                try (var workbook = new org.apache.poi.xssf.usermodel.XSSFWorkbook(new java.io.ByteArrayInputStream(downloaded.body()))) {
                    assertThat(workbook.getNumberOfSheets()).isEqualTo(31);
                    assertThat(workbook.getSheet("취약점 상세")).isNotNull();
                    assertThat(workbook.getSheet("애플리케이션_오픈소스").getRow(2).getCell(7).getStringCellValue()).isEqualTo("취약점발견");
                }
            }
        }
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
