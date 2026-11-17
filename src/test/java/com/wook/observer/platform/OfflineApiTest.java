package com.wook.observer.platform;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import tools.jackson.databind.json.JsonMapper;

import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.datasource.url=jdbc:h2:mem:offlineapi;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE",
        "spring.datasource.username=sa", "spring.datasource.password=",
        "observer.platform.bootstrap-user=admin", "observer.platform.bootstrap-password=Init1234",
        "observer.platform.encryption-key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="})
class OfflineApiTest {
    @Value("${local.server.port}") int port;
    @Autowired Accounts accounts;
    @Autowired Store store;
    private final JsonMapper json = JsonMapper.builder().build();

    @Test
    void authenticatesDownloadsAndRequiresAdminAndCsrfForImports() throws Exception {
        var anonymous = new Client();
        assertThat(anonymous.get("/offline.js").statusCode()).isEqualTo(200);
        assertThat(anonymous.get("/network.js").statusCode()).isEqualTo(200);
        assertThat(anonymous.get("/asset-report.js").statusCode()).isEqualTo(200);
        assertThat(anonymous.get("/api/collector/observer-collect.sh").statusCode()).isEqualTo(401);
        assertThat(anonymous.get("/api/assets/report.md").statusCode()).isEqualTo(401);
        accounts.create("offline-reader", "Reader1234", "USER", true);
        var reader = login("offline-reader", "Reader1234");
        assertThat(reader.get("/api/collector/observer-collect.sh").body()).contains("#!/bin/sh", "sha256sum").doesNotContain("# OBSERVER_INVENTORY");
        assertThat(reader.get("/api/assets/report.md").statusCode()).isEqualTo(200);
        assertThat(reader.upload("/api/admin/offline/preview", true).statusCode()).isEqualTo(403);
        assertThat(reader.upload("/api/admin/offline/import", true).statusCode()).isEqualTo(403);
        var admin = login("admin", "Init1234");
        assertThat(admin.upload("/api/admin/offline/import", false).statusCode()).isEqualTo(403);
        assertThat(admin.upload("/api/admin/offline/preview", true).statusCode()).isEqualTo(200);
        var response = admin.upload("/api/admin/offline/import", true);
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        String target = json.readTree(response.body()).path("targetId").asText();
        var markdown = reader.get("/api/assets/" + target + "/report.md");
        assertThat(markdown.statusCode()).isEqualTo(200);
        assertThat(markdown.headers().firstValue("Content-Type").orElseThrow()).startsWith("text/markdown");
        assertThat(markdown.headers().firstValue("Cache-Control").orElseThrow()).contains("no-store");
        assertThat(markdown.body()).contains("api-fixture", "12345", "TCP 0.0.0.0:8080", "파일 업로드");
        assertThat(reader.get("/api/assets/" + target + "/export.xlsx").statusCode()).isEqualTo(200);
    }

    @Test
    void logsSafeImportMetadataWithoutUploadedContent() throws Exception {
        var admin = login("admin", "Init1234");
        try (var log = new OperationLogCapture(ch.qos.logback.classic.Level.INFO)) {
            var result = admin.upload("/api/admin/offline/import", true);
            assertThat(result.statusCode()).isEqualTo(200);
            assertThat(log.text()).contains("event=http.completed").doesNotContain("content-canary", "api-fixture", "PROCESS\\t");
        }
    }

    private Client login(String username, String password) throws Exception {
        var client = new Client(); client.refresh();
        var request = HttpRequest.newBuilder(uri("/api/login")).header("X-CSRF-TOKEN", client.csrf)
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString("username=" + username + "&password=" + password)).build();
        assertThat(client.http.send(request, HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(204);
        client.refresh(); return client;
    }
    private URI uri(String path) { return URI.create("http://127.0.0.1:" + port + path); }
    private class Client {
        final HttpClient http = HttpClient.newBuilder().cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL)).build();
        String csrf;
        void refresh() throws Exception { csrf = json.readTree(get("/api/session").body()).path("csrf").asText(); }
        HttpResponse<String> get(String path) throws Exception { return http.send(HttpRequest.newBuilder(uri(path)).GET().build(), HttpResponse.BodyHandlers.ofString()); }
        HttpResponse<String> upload(String path, boolean token) throws Exception {
            String boundary = "ObserverFixtureBoundary";
            var body = new StringBuilder();
            for (var field : Map.of("name", "API 자산", "host", "api-fixture").entrySet()) body.append("--").append(boundary)
                    .append("\r\nContent-Disposition: form-data; name=\"").append(field.getKey()).append("\"\r\n\r\n").append(field.getValue()).append("\r\n");
            body.append("--").append(boundary).append("\r\nContent-Disposition: form-data; name=\"file\"; filename=\"content-canary.observer\"\r\nContent-Type: application/octet-stream\r\n\r\n")
                    .append(new String(OfflineInventoryTest.file("api-fixture"), StandardCharsets.UTF_8)).append("\r\n--").append(boundary).append("--\r\n");
            var request = HttpRequest.newBuilder(uri(path)).header("Content-Type", "multipart/form-data; boundary=" + boundary);
            if (token) request.header("X-CSRF-TOKEN", csrf);
            return http.send(request.POST(HttpRequest.BodyPublishers.ofString(body.toString())).build(), HttpResponse.BodyHandlers.ofString());
        }
    }
}
