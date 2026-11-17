package com.wook.observer.platform;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

import java.io.ByteArrayOutputStream;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.datasource.url=jdbc:h2:mem:trash;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE",
        "spring.datasource.username=sa", "spring.datasource.password=",
        "observer.platform.bootstrap-user=admin", "observer.platform.bootstrap-password=Init1234",
        "observer.platform.encryption-key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="})
class AssetTrashTest {
    @Autowired Store store;
    @Autowired Assets assets;
    @Autowired AssetTrash trash;
    @Autowired Targets targets;
    @Autowired OfflineImports imports;
    @Autowired ScanJobs jobs;
    @Autowired AssetBootstrap bootstrap;
    @Autowired Accounts accounts;
    @Autowired PlatformTransactionManager manager;
    @org.springframework.test.context.bean.override.mockito.MockitoSpyBean SshCollector collector;
    @Value("${local.server.port}") int port;
    private final JsonMapper json = JsonMapper.builder().build();

    @Test
    void restoresWholeAssetWithContractCredentialsServicesAndOriginalReports() throws Exception {
        String target = target();
        assets.saveProfile(target, new Assets.ProfileInput("TRASH-" + target, "PRODUCTION", "운영팀", "IDC", "업무", "ACTIVE", "보존", 0, true, "2029-05-31"), "admin");
        String job = complete(target);
        String service = assets.saveService(target, null, service("보존 서비스", true, 0), "admin");
        var before = assets.details(target);
        var profile = store.one("SELECT * FROM asset_profile WHERE target_id=?", target);
        var inventory = store.one("SELECT * FROM asset_inventory WHERE target_id=?", target);
        var rows = store.rows("SELECT * FROM asset_service WHERE target_id=? ORDER BY id", target);
        var report = store.one("SELECT * FROM scan_job WHERE id=?", job);
        var credentials = targets.credentials(target);
        delete(target, 0);
        assertThat(items(assets.list(), "items")).noneMatch(a -> target.equals(a.get("id")));
        assertThat(targets.list()).noneMatch(a -> target.equals(a.get("id")));
        assertThat(items(trash.list(), "assets")).anySatisfy(a -> assertThat(a).containsEntry("id", target).containsEntry("deleted_by", "admin").containsEntry("job_count", 1L));
        assertThatThrownBy(() -> assets.details(target)).isInstanceOf(ApiProblem.class);
        assertThatThrownBy(() -> assets.markdown(target)).isInstanceOf(ApiProblem.class);
        assertThat(assets.markdown(null)).doesNotContain("TRASH-" + target);
        var output = new ByteArrayOutputStream(); assets.export(null, output);
        try (var book = new org.apache.poi.xssf.usermodel.XSSFWorkbook(new java.io.ByteArrayInputStream(output.toByteArray()))) {
            assertThat(new org.apache.poi.xssf.extractor.XSSFExcelExtractor(book).getText()).doesNotContain("TRASH-" + target);
        }
        bootstrap.run(null); // Deleted assets never break startup backfill or silently return to the ledger.
        trash.restoreAsset(target, new AssetTrash.RevisionInput(1), "admin");
        assertThat(store.one("SELECT * FROM asset_profile WHERE target_id=?", target)).isEqualTo(profile);
        assertThat(store.one("SELECT * FROM asset_inventory WHERE target_id=?", target)).isEqualTo(inventory);
        assertThat(store.rows("SELECT * FROM asset_service WHERE target_id=? ORDER BY id", target)).isEqualTo(rows);
        assertThat(store.one("SELECT * FROM scan_job WHERE id=?", job)).isEqualTo(report);
        var restored = targets.credentials(target);
        restored.remove("trash_revision"); credentials.remove("trash_revision");
        assertThat(restored).isEqualTo(credentials);
        assertThat(assets.details(target).get("services")).isEqualTo(before.get("services"));
        assertThat(items(assets.details(target), "history")).extracting(e -> e.get("action")).containsExactly("ASSET_RESTORED", "ASSET_DELETED");
        assertThat(items(assets.details(target), "services")).anySatisfy(s -> assertThat(s).containsEntry("id", service).containsEntry("archived", true));
    }

    @Test
    void rejectsWrongNameStaleRequestsAndEditsToDeletedAssets() {
        String target = target();
        assertThatThrownBy(() -> trash.deleteAsset(target, new AssetTrash.AssetInput("wrong", 0), "admin")).isInstanceOf(ApiProblem.class);
        assertThatThrownBy(() -> trash.deleteAsset(target, new AssetTrash.AssetInput(name(target), null), "admin")).isInstanceOf(ApiProblem.class);
        assertThat(store.rows("SELECT id FROM asset_management_event WHERE target_id=?", target)).isEmpty();
        delete(target, 0);
        assertThatThrownBy(() -> delete(target, 0)).isInstanceOf(ApiProblem.class);
        assertThatThrownBy(() -> trash.restoreAsset(target, new AssetTrash.RevisionInput(0), "admin")).isInstanceOf(ApiProblem.class);
        assertThatThrownBy(() -> targets.saveUpload(target, new Targets.UploadInput("renamed", "fixture", ""), "admin")).isInstanceOf(ApiProblem.class).hasMessageContaining("휴지통");
        assertThatThrownBy(() -> assets.saveProfile(target, new Assets.ProfileInput("", "TEST", "", "", "", "ACTIVE", "", 0), "admin")).isInstanceOf(ApiProblem.class).hasMessageContaining("휴지통");
        assertThatThrownBy(() -> assets.saveService(target, null, service("new", false, 0), "admin")).isInstanceOf(ApiProblem.class).hasMessageContaining("휴지통");
        assertThatThrownBy(() -> jobs.start(target, "admin")).isInstanceOf(ApiProblem.class).hasMessageContaining("휴지통");
        trash.restoreAsset(target, new AssetTrash.RevisionInput(1), "admin");
        assertThatThrownBy(() -> delete(target, 0)).isInstanceOf(ApiProblem.class);
    }

    @Test
    void blocksDeletionDuringQueuedAndRunningScans() {
        String target = target(), job = queued(target);
        assertThatThrownBy(() -> delete(target, 0)).isInstanceOf(ApiProblem.class).hasMessageContaining("점검 중");
        store.jdbc.update("UPDATE scan_job SET status='RUNNING' WHERE id=?", job);
        assertThatThrownBy(() -> delete(target, 0)).isInstanceOf(ApiProblem.class).hasMessageContaining("점검 중");
        store.jdbc.update("UPDATE scan_job SET status='FAILED' WHERE id=?", job);
        delete(target, 0);
    }

    @Test
    void deletedObservedServiceStaysDeletedOnRescanAndRestoresSameManualFields() {
        String target = target(); complete(target);
        String service = Store.string(items(assets.details(target), "services").getFirst(), "id");
        assets.saveService(target, service, service("REMOVED-" + service, true, 0), "admin");
        trash.deleteService(target, service, new AssetTrash.RevisionInput(1), "admin");
        assertThat(items(assets.details(target), "services")).isEmpty();
        assertThat(assets.markdown(target)).doesNotContain("REMOVED-" + service);
        assertThatThrownBy(() -> assets.saveService(target, service, service("stale", false, 1), "admin")).isInstanceOf(ApiProblem.class);
        complete(target);
        assertThat(items(assets.details(target), "services")).isEmpty();
        assertThat(store.rows("SELECT id FROM asset_service WHERE target_id=?", target)).hasSize(1);
        assertThat(items(trash.list(), "services")).anySatisfy(s -> assertThat(s).containsEntry("id", service).containsEntry("revision", 2));
        assertThatThrownBy(() -> trash.restoreService(target, service, new AssetTrash.RevisionInput(1), "admin")).isInstanceOf(ApiProblem.class);
        trash.restoreService(target, service, new AssetTrash.RevisionInput(2), "admin");
        assertThat(items(assets.details(target), "services").getFirst()).containsEntry("id", service).containsEntry("revision", 3)
                .containsEntry("name", "REMOVED-" + service).containsEntry("start_command", "start-example").containsEntry("archived", true);
    }

    @Test
    void parentRestorationDoesNotRestoreIndividuallyDeletedServicesAndCrossAssetIdsFail() {
        String target = target(), other = target();
        String service = assets.saveService(target, null, service("manual", false, 0), "admin");
        assertThatThrownBy(() -> trash.deleteService(other, service, new AssetTrash.RevisionInput(1), "admin")).isInstanceOf(ApiProblem.class);
        trash.deleteService(target, service, new AssetTrash.RevisionInput(1), "admin");
        delete(target, 0);
        assertThat(items(trash.list(), "services")).noneMatch(s -> service.equals(s.get("id")));
        assertThatThrownBy(() -> trash.restoreService(target, service, new AssetTrash.RevisionInput(2), "admin")).isInstanceOf(ApiProblem.class).hasMessageContaining("휴지통");
        trash.restoreAsset(target, new AssetTrash.RevisionInput(1), "admin");
        assertThat(items(assets.details(target), "services")).isEmpty();
        assertThat(items(trash.list(), "services")).anyMatch(s -> service.equals(s.get("id")));
    }

    @Test
    void duplicateAndNewUploadsCannotReviveDeletedAssets() {
        byte[] file = OfflineInventoryTest.file("trash-" + UUID.randomUUID());
        var imported = imports.importFile(file, "", "imported", "fixture", false, "admin");
        String target = Store.string(imported, "targetId");
        delete(target, 0);
        assertThatThrownBy(() -> imports.preview(file, "")).isInstanceOf(ApiProblem.class).hasMessageContaining("휴지통");
        assertThatThrownBy(() -> imports.importFile(file, "", "duplicate", "fixture", false, "admin")).isInstanceOf(ApiProblem.class).hasMessageContaining("휴지통");
        assertThatThrownBy(() -> imports.importFile(OfflineInventoryTest.file("new-host"), target, "", "", true, "admin")).isInstanceOf(ApiProblem.class).hasMessageContaining("휴지통");
        trash.restoreAsset(target, new AssetTrash.RevisionInput(1), "admin");
        assertThat(imports.importFile(file, target, "", "", false, "admin")).containsEntry("duplicate", true).containsEntry("id", imported.get("id"));
    }

    @Test
    void deletionAndScanSubmissionShareParentLock() throws Exception {
        String target = target();
        var locked = new CountDownLatch(1); var release = new CountDownLatch(1);
        var deleting = CompletableFuture.runAsync(() -> new TransactionTemplate(manager).executeWithoutResult(status -> {
            delete(target, 0); locked.countDown();
            try { if (!release.await(10, TimeUnit.SECONDS)) throw new AssertionError("lock timeout"); }
            catch (InterruptedException e) { throw new RuntimeException(e); }
        }));
        assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();
        var scan = CompletableFuture.runAsync(() -> assertThatThrownBy(() -> jobs.start(target, "admin"))
                .isInstanceOf(ApiProblem.class).hasMessageContaining("휴지통"));
        release.countDown(); deleting.get(10, TimeUnit.SECONDS); scan.get(10, TimeUnit.SECONDS);
        assertThat(store.rows("SELECT id FROM scan_job WHERE target_id=?", target)).isEmpty();
    }

    @Test
    void scanWorkerSeesCommittedJobAndDeletionWaitsForCompletion() throws Exception {
        String target = targets.save(null, new Targets.Input("SSH concurrency", "192.0.2.1", 22, "observer", "",
                "ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIAABAgMEBQYHCAkKCwwNDg8QERITFBUWFxgZGhscHR4f", "",
                "PASSWORD", "Fixture1234", "", "NONE", ""), "admin");
        var started = new CountDownLatch(1); var release = new CountDownLatch(1);
        org.mockito.Mockito.doAnswer(invocation -> {
            started.countDown();
            if (!release.await(10, TimeUnit.SECONDS)) throw new AssertionError("worker timeout");
            return ServiceDiscoveryTest.host(100, 8080);
        }).when(collector).collect(org.mockito.ArgumentMatchers.anyMap());
        String job = jobs.start(target, "admin");
        try {
            assertThat(started.await(10, TimeUnit.SECONDS)).isTrue();
            assertThat(store.one("SELECT status FROM scan_job WHERE id=?", job)).containsEntry("status", "RUNNING");
            assertThatThrownBy(() -> delete(target, 0)).isInstanceOf(ApiProblem.class).hasMessageContaining("점검 중");
        } finally { release.countDown(); }
        org.awaitility.Awaitility.await().atMost(java.time.Duration.ofSeconds(10)).untilAsserted(() ->
                assertThat(store.one("SELECT status FROM scan_job WHERE id=?", job)).containsEntry("status", "COMPLETED"));
        delete(target, 0);
        trash.restoreAsset(target, new AssetTrash.RevisionInput(1), "admin");
        assertThat(targets.decrypt(targets.credentials(target), "ssh_password")).isEqualTo("Fixture1234");
        assertThat(jobs.report(job).processes()).isNotEmpty();
    }

    @Test
    void apiRequiresAdministratorCsrfAndNeverReturnsCredentials() throws Exception {
        String target = target(), service = assets.saveService(target, null, service("api", false, 0), "admin");
        String username = "reader-" + UUID.randomUUID().toString().substring(0, 8);
        accounts.create(username, "Reader1234", "USER", true);
        var reader = new Client(username, "Reader1234"); var admin = new Client("admin", "Init1234");
        var body = Map.of("name", name(target), "revision", 0);
        String path = "/api/admin/assets/" + target;
        assertThat(reader.call("GET", "/api/admin/assets/trash", null, true).statusCode()).isEqualTo(403);
        for (String action : List.of("DELETE", "POST")) {
            String suffix = action.equals("POST") ? "/restore" : "";
            assertThat(reader.call(action, path + suffix, body, true).statusCode()).isEqualTo(403);
            assertThat(admin.call(action, path + suffix, body, false).statusCode()).isEqualTo(403);
            assertThat(reader.call(action, path + "/services/" + service + suffix, Map.of("revision", 1), true).statusCode()).isEqualTo(403);
        }
        assertThat(admin.call("DELETE", path, body, true).statusCode()).isEqualTo(200);
        var listing = admin.call("GET", "/api/admin/assets/trash", null, true);
        assertThat(listing.statusCode()).isEqualTo(200);
        assertThat(listing.body()).doesNotContain("private_key", "ssh_password", "privilege_password", "host_key");
        assertThat(reader.call("GET", "/api/assets/" + target, null, true).statusCode()).isEqualTo(404);
        assertThat(admin.call("POST", path + "/restore", Map.of("revision", 1), true).statusCode()).isEqualTo(200);
        assertThat(reader.call("GET", "/api/assets/" + target, null, true).statusCode()).isEqualTo(200);
    }

    private String target() { return targets.saveUpload(null, new Targets.UploadInput("자산-" + UUID.randomUUID(), "fixture", "보존할 설명"), "admin"); }
    private String name(String target) { return Store.string(store.one("SELECT name FROM scan_target WHERE id=?", target), "name"); }
    private void delete(String target, int revision) { trash.deleteAsset(target, new AssetTrash.AssetInput(name(target), revision), "admin"); }
    private Assets.ServiceInput service(String name, boolean archived, int revision) {
        return new Assets.ServiceInput(name, "TCP 8080", "운영팀", "SW", "1", "app", "DATABASE", "/app", "/data", "/log", "start-example", "stop-example", "status-example", "dependency", "memo", archived, revision);
    }
    private String queued(String target) {
        String id = UUID.randomUUID().toString();
        store.jdbc.update("INSERT INTO scan_job(id, target_id, target_name, requested_by, status, created_at) VALUES (?, ?, ?, 'admin', 'QUEUED', ?)", id, target, name(target), Instant.now().toString());
        return id;
    }
    private String complete(String target) { String job = queued(target); assets.complete(job, ServiceDiscoveryTest.host(100, 8080), 0); return job; }
    @SuppressWarnings("unchecked") private List<Map<String, Object>> items(Map<String, Object> result, String key) { return (List<Map<String, Object>>) result.get(key); }
    private class Client {
        final HttpClient http = HttpClient.newBuilder().cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL)).build();
        String csrf;
        Client(String username, String password) throws Exception {
            csrf = json.readTree(call("GET", "/api/session", null, false).body()).path("csrf").asText();
            var login = HttpRequest.newBuilder(uri("/api/login")).header("X-CSRF-TOKEN", csrf).header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.ofString("username=" + username + "&password=" + password)).build();
            assertThat(http.send(login, HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(204);
            csrf = json.readTree(call("GET", "/api/session", null, false).body()).path("csrf").asText();
        }
        HttpResponse<String> call(String method, String path, Object body, boolean token) throws Exception {
            var request = HttpRequest.newBuilder(uri(path)).header("Content-Type", "application/json");
            if (token) request.header("X-CSRF-TOKEN", csrf);
            return http.send(request.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))).build(), HttpResponse.BodyHandlers.ofString());
        }
        URI uri(String path) { return URI.create("http://127.0.0.1:" + port + path); }
    }
}
