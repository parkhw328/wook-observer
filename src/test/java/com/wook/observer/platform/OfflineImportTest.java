package com.wook.observer.platform;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:offline;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE",
        "spring.datasource.username=sa", "spring.datasource.password=",
        "observer.platform.bootstrap-user=admin", "observer.platform.bootstrap-password=Init1234",
        "observer.platform.encryption-key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="})
class OfflineImportTest {
    @Autowired OfflineImports imports;
    @Autowired Assets assets;
    @Autowired Targets targets;
    @Autowired ScanJobs jobs;
    @Autowired Store store;

    @Test
    void existingAssetPreviewAndReimportRespectTheSavedElsContract() {
        String os = java.util.Base64.getEncoder().encodeToString("ID=rhel\nVERSION_ID=7.9\nPRETTY_NAME=\"Red Hat Enterprise Linux Server 7.9 (Maipo)\"".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        String payload = OfflineInventoryTest.payload("els-host").replace("DONE\n", "OSINFO\tos_release\tCOLLECTED\t" + os + "\nDONE\n");
        byte[] first = OfflineInventoryTest.file(payload, Instant.parse("2026-01-01T00:00:00Z"));
        String target = imports.importFile(first, "", "ELS 자산", "els-host", false, "admin").get("targetId").toString();
        assets.saveProfile(target, new Assets.ProfileInput("ELS", "PRODUCTION", "운영팀", "IDC", "업무", "ACTIVE", "", 0, true, "2029-05-31"), "admin");
        byte[] next = OfflineInventoryTest.file(payload.replace("12345", "54321"), Instant.parse("2026-02-01T00:00:00Z"));
        assertThat(((OsLifecycle.Support) imports.preview(next, target).get("osSupport")).status()).isEqualTo("ELS_RECORDED");
        assertThat(((OsLifecycle.Support) imports.preview(next, "").get("osSupport")).upgradeRequired()).isTrue();
        imports.importFile(next, target, "", "", false, "admin");
        var asset = (Map<String, Object>) assets.details(target).get("asset");
        assertThat(asset).containsEntry("els_contract", true).containsEntry("els_valid_until", "2029-05-31");
        assertThat(((OsLifecycle.Support) asset.get("os_support")).status()).isEqualTo("ELS_RECORDED");
    }

    @Test
    void createsCredentialFreeAssetsAndDeduplicatesImportsAtomically() {
        byte[] file = OfflineInventoryTest.file("new-host");
        var preview = imports.preview(file, "");
        assertThat(preview).containsEntry("identityMismatch", false);
        var result = imports.importFile(file, "", "새 자산", "192.0.2.15", false, "admin");
        String target = result.get("targetId").toString(), job = result.get("id").toString();
        assertThat(targets.details(target)).containsEntry("collection_mode", "UPLOAD").containsEntry("hasSshPassword", false).containsEntry("hasPrivateKey", false);
        assertThat(jobs.report(job).assessment()).isNotNull();
        assertThat(imports.importFile(file, "", "중복", "new", false, "admin")).containsEntry("id", job).containsEntry("duplicate", true);
        assertThat(imports.importFile(file, target, "", "", false, "admin")).containsEntry("id", job).containsEntry("duplicate", true);
        assertThat(store.jdbc.queryForObject("SELECT COUNT(*) FROM scan_job WHERE target_id=?", Long.class, target)).isEqualTo(1);
        assertThatThrownBy(() -> jobs.start(target, "admin")).isInstanceOf(ApiProblem.class).hasMessageContaining("파일 업로드");
    }

    @Test
    void oldFilesRemainHistoryWithoutReplacingLatestInventoryOrHandwrittenFields() {
        var first = imports.importFile(OfflineInventoryTest.file("chronology-host"), "", "chronology", "chronology-host", false, "admin");
        String target = first.get("targetId").toString();
        assets.saveProfile(target, new Assets.ProfileInput("ASSET", "PRODUCTION", "운영팀", "IDC 1층", "업무", "ACTIVE", "수기 보존", 0), "admin");
        var detail = assets.details(target);
        var service = ((List<Map<String, Object>>) detail.get("services")).getFirst();
        assets.saveService(target, service.get("id").toString(), new Assets.ServiceInput("업무 웹", "TCP 8080", "담당", "nginx", "1", "operator", "APPLICATION", "/app", "/data", "/log", "start", "stop", "status", "db", "메모", false, 0), "admin");
        String raw = OfflineInventoryTest.payload("chronology-host").replace("12345", "54321");
        var latest = imports.importFile(OfflineInventoryTest.file(raw, Instant.parse("2026-02-01T00:00:00Z")), target, "", "", false, "admin");
        var old = imports.importFile(OfflineInventoryTest.file(raw.replace("54321", "99999"), Instant.parse("2025-02-01T00:00:00Z")), target, "", "", false, "admin");
        assertThat(old).containsEntry("currentInventory", false);
        assertThat(latest).containsEntry("currentInventory", true);
        var current = assets.details(target);
        assertThat((Map<String, Object>) current.get("asset")).containsEntry("job_id", latest.get("id")).containsEntry("location", "IDC 1층");
        var row = ((List<Map<String, Object>>) current.get("services")).getFirst();
        assertThat(row).containsEntry("name", "업무 웹").containsEntry("start_command", "start").containsEntry("revision", 1);
        assertThat(((ServiceDiscovery.Candidate) row.get("observed")).pids()).containsExactly(54321L);
        assertThat(jobs.report(old.get("id").toString()).processes().getFirst().pid()).isEqualTo(99999);
        assertThat(assets.markdown(target)).contains("서버 위치", "IDC 1층", "업무 웹", "54321", "```text\nstart").doesNotContain("99999");
    }

    @Test
    void rejectsIdentityMismatchAndCrossAssetDuplicateWithoutMutation() {
        var first = imports.importFile(OfflineInventoryTest.file("identity-original"), "", "identity", "identity-original", false, "admin");
        String target = first.get("targetId").toString();
        byte[] changed = OfflineInventoryTest.file("identity-changed");
        assertThat(imports.preview(changed, target)).containsEntry("identityMismatch", true).containsEntry("expectedHost", "identity-original");
        assertThatThrownBy(() -> imports.importFile(changed, target, "", "", false, "admin")).isInstanceOf(ApiProblem.class).hasMessageContaining("호스트명");
        assertThat(store.jdbc.queryForObject("SELECT COUNT(*) FROM scan_job WHERE target_id=?", Long.class, target)).isEqualTo(1);
        imports.importFile(changed, target, "", "", true, "admin");
        String other = targets.saveUpload(null, new Targets.UploadInput("other", "other", ""), "admin");
        assertThatThrownBy(() -> imports.importFile(changed, other, "", "", true, "admin")).isInstanceOf(ApiProblem.class).hasMessageContaining("다른 자산");
        assertThat(store.jdbc.queryForObject("SELECT COUNT(*) FROM scan_job WHERE target_id=?", Long.class, other)).isZero();
    }

    @Test
    void failedValidationLeavesAssetsAndJobsUnchanged() {
        int targetsBefore = targets.list().size();
        assertThatThrownBy(() -> imports.importFile(new byte[]{1,2,3}, "", "must-not-create", "host", false, "admin")).isInstanceOf(ApiProblem.class);
        assertThat(targets.list()).hasSize(targetsBefore);
        assertThatThrownBy(() -> imports.importFile(OfflineInventoryTest.file("invalid-asset"), "", "", "bad host", false, "admin")).isInstanceOf(ApiProblem.class);
        assertThat(targets.list()).hasSize(targetsBefore);
    }

    @Test
    void concurrentRetriesCreateExactlyOneAssetAndJob() throws Exception {
        byte[] file = OfflineInventoryTest.file("concurrent-host");
        int before = targets.list().size();
        try (var executor = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var start = new java.util.concurrent.CountDownLatch(1);
            java.util.concurrent.Callable<Map<String, Object>> call = () -> {
                start.await(); return imports.importFile(file, "", "concurrent", "concurrent-host", false, "admin");
            };
            var first = executor.submit(call); var second = executor.submit(call); start.countDown();
            var a = first.get(20, java.util.concurrent.TimeUnit.SECONDS); var b = second.get(20, java.util.concurrent.TimeUnit.SECONDS);
            assertThat(a.get("id")).isEqualTo(b.get("id"));
            assertThat(a.get("duplicate")).isNotEqualTo(b.get("duplicate"));
        }
        assertThat(targets.list()).hasSize(before + 1);
    }

    @Test
    void persistenceFailureRollsBackNewAssetAndOmitsSuccessLog() {
        int before = targets.list().size();
        try (var log = new OperationLogCapture(ch.qos.logback.classic.Level.INFO)) {
            assertThatThrownBy(() -> imports.importFile(OfflineInventoryTest.file("rollback-host"), "", "rollback", "rollback-host", false, "missing-actor"))
                    .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
            assertThat(log.text()).doesNotContain("event=offline.imported", "event=target.upload.saved");
        }
        assertThat(targets.list()).hasSize(before);
    }
}
