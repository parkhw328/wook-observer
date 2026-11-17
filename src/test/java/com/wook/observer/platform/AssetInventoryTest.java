package com.wook.observer.platform;

import com.wook.observer.model.Observation;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:assets;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE",
        "spring.datasource.username=sa", "spring.datasource.password=",
        "observer.platform.bootstrap-user=admin", "observer.platform.bootstrap-password=Init1234",
        "observer.platform.encryption-key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="})
class AssetInventoryTest {
    @Autowired Assets assets;
    @Autowired Store store;
    @Autowired AssetBootstrap bootstrap;
    private final JsonMapper json = JsonMapper.builder().build();

    @Test
    void elsContractSurvivesRescansAndOldClientsAndCanBeExplicitlyCleared() {
        String target = target();
        var report = OsLifecycleTest.report("ID=rhel\nVERSION_ID=7.9", "");
        complete(target, report, 0);
        assets.saveProfile(target, contract(0, true, "2029-05-31"), "admin");
        var saved = asset(target);
        assertThat(saved).containsEntry("els_contract", true).containsEntry("els_valid_until", "2029-05-31").containsEntry("els_confirmed_by", "admin");
        assertThat(Store.string(saved, "els_confirmed_at")).isNotEmpty();
        assertThat(((OsLifecycle.Support) saved.get("os_support")).status()).isEqualTo("ELS_RECORDED");
        complete(target, report, 0);
        assets.saveProfile(target, profile(1), "admin");
        assertThat(asset(target)).containsEntry("els_contract", true).containsEntry("els_confirmed_at", saved.get("els_confirmed_at"));
        assets.saveProfile(target, contract(2, true, "2029-05-31"), "admin");
        assertThat(asset(target)).containsEntry("els_confirmed_at", saved.get("els_confirmed_at"));
        assets.saveProfile(target, contract(3, false, ""), "admin");
        assertThat(asset(target)).containsEntry("els_contract", false).containsEntry("els_valid_until", "").containsEntry("els_confirmed_by", "");
        assertThat(((OsLifecycle.Support) asset(target).get("os_support")).upgradeRequired()).isTrue();
    }

    @Test
    void invalidContractDatesRollBackTheEntireProfileEdit() {
        String target = target();
        assets.saveProfile(target, profile(0), "admin");
        var before = asset(target);
        for (String invalid : List.of("", "bad", "2026-02-30", "2024-06-30", "2029-06-01")) {
            assertThatThrownBy(() -> assets.saveProfile(target, contract(1, true, invalid), "admin")).isInstanceOf(ApiProblem.class);
            assertThat(asset(target)).isEqualTo(before);
        }
    }

    private Assets.ProfileInput contract(int revision, boolean checked, String end) {
        return new Assets.ProfileInput("ELS-01", "PRODUCTION", "운영팀", "IDC", "업무", "ACTIVE", "계약 확인", revision, checked, end);
    }

    @Test
    void savesExpandedServiceTypesAndPreservesThemAcrossObservationRefresh() {
        String target = target();
        complete(target, ServiceDiscoveryTest.host(100, 8080), 0);
        String id = Store.string(services(target).getFirst(), "id");
        int revision = 0;
        for (String type : List.of("WEB", "DATABASE", "CONTAINER", "CACHE", "MESSAGING", "INFRASTRUCTURE", "MIDDLEWARE", "APPLICATION", "COMMON", "OTHER")) {
            var input = new Assets.ServiceInput("업무 서비스", "TCP 8080", "운영팀", "", "", "", type,
                    "", "", "", "start", "stop", "status", "", "", false, revision++);
            assets.saveService(target, id, input, "admin");
            complete(target, ServiceDiscoveryTest.host(100 + revision, 8080), 0);
            assertThat(services(target).getFirst()).containsEntry("service_type", type).containsEntry("revision", revision);
        }
    }

    @Test
    void enrichesLegacyOsProjectionWithoutChangingReportsOrHandwrittenAssetData() {
        String target = target();
        var report = OsLifecycleTest.report("", "CentOS Linux release 7.9.2009 (Core)");
        String job = complete(target, report, 0);
        assets.saveProfile(target, profile(0), "admin");
        String original = Store.string(store.one("SELECT report_json FROM scan_job WHERE id=?", job), "report_json");
        var tree = (tools.jackson.databind.node.ObjectNode) json.readTree(Store.string(store.one("SELECT summary_json FROM asset_inventory WHERE target_id=?", target), "summary_json"));
        tree.remove("osIdentity"); tree.put("distribution", "Linux 3.10.0.el7");
        store.jdbc.update("UPDATE asset_inventory SET summary_json=? WHERE target_id=?", json.writeValueAsString(tree), target);
        assertThat(assets.enrichOsIdentity()).isGreaterThanOrEqualTo(1);
        var asset = (Map<String, Object>) assets.details(target).get("asset");
        assertThat(((OsLifecycle.Support) asset.get("os_support")).status()).isEqualTo("END_OF_LIFE");
        assertThat(((ServiceDiscovery.Summary) asset.get("inventory")).distribution()).contains("CentOS Linux");
        assertThat(asset).containsEntry("revision", 1).containsEntry("job_id", job).containsEntry("finding_count", 0);
        assertThat(Store.string(store.one("SELECT report_json FROM scan_job WHERE id=?", job), "report_json")).isEqualTo(original);
        assertThat(assets.enrichOsIdentity()).isZero();
    }

    @Test
    void annotationsSurviveRestartAbsenceRediscoveryAndArchiving() {
        String target = target();
        var first = ServiceDiscoveryTest.host(100, 8080);
        String firstJob = complete(target, first, 3);
        var service = services(target).getFirst();
        String id = Store.string(service, "id");
        assets.saveService(target, id, input("업무 웹", false, 0), "admin");
        assets.saveProfile(target, profile(0), "admin");
        complete(target, ServiceDiscoveryTest.host(900, 8080), 2);
        var restarted = services(target).getFirst();
        assertThat(services(target)).hasSize(1);
        assertThat(restarted).containsEntry("id", id).containsEntry("name", "업무 웹").containsEntry("revision", 1)
                .containsEntry("status_command", "systemctl status example").containsEntry("observation_status", "OBSERVED")
                .containsEntry("data_path", "/srv/data");
        assertThat(((ServiceDiscovery.Candidate) restarted.get("observed")).pids()).containsExactly(900L);
        String snapshot = Store.string(store.one("SELECT report_json FROM scan_job WHERE id=?", firstJob), "report_json");
        complete(target, new Observation("1.6.0", Instant.now(), first.host(), List.of(), List.of(), List.of(), List.of()), 0);
        assertThat(services(target).getFirst()).containsEntry("observation_status", "NOT_OBSERVED").containsEntry("start_command", "systemctl start example");
        assets.saveService(target, id, input("업무 웹", true, 1), "admin");
        complete(target, ServiceDiscoveryTest.host(1000, 8080), 1);
        assertThat(services(target).getFirst()).containsEntry("archived", true).containsEntry("revision", 2).containsEntry("observation_status", "OBSERVED");
        assertThat(asset(target)).containsEntry("owner", "플랫폼팀").containsEntry("service_count", 0L);
        assertThat(Store.string(store.one("SELECT report_json FROM scan_job WHERE id=?", firstJob), "report_json")).isEqualTo(snapshot);
    }

    @Test
    void staleEditsAreRejectedAndServicesCannotCrossTargets() {
        String target = target(), other = target();
        String id = assets.saveService(target, null, input("manual", false, 0), "admin");
        assertThat(services(target).getFirst()).containsEntry("observation_status", "MANUAL");
        assertThatThrownBy(() -> assets.saveService(target, id, input("lost edit", false, 0), "admin")).isInstanceOf(ApiProblem.class).hasMessageContaining("다른 사용자");
        assertThatThrownBy(() -> assets.saveService(other, id, input("cross", false, 1), "admin")).isInstanceOf(ApiProblem.class).hasMessageContaining("찾을 수");
        assets.saveProfile(target, profile(0), "admin");
        assertThatThrownBy(() -> assets.saveProfile(target, profile(0), "admin")).isInstanceOf(ApiProblem.class).hasMessageContaining("다른 사용자");
        assertThatThrownBy(() -> assets.saveService(target, null, input("x".repeat(161), false, 0), "admin")).isInstanceOf(ApiProblem.class);
        assertThat(services(target)).hasSize(1);
        assertThat(services(target).getFirst()).containsEntry("name", "manual");
    }

    @Test
    void latestCompletedSnapshotIsIndependentOfGlobalHistoryAndFailedAttempts() {
        String target = target();
        String completed = complete(target, ServiceDiscoveryTest.host(200, 443), 7);
        for (int i = 0; i < 105; i++) {
            String job = queued(target);
            store.jdbc.update("UPDATE scan_job SET status='FAILED', message='fixture failure' WHERE id=?", job);
        }
        assertThat(asset(target)).containsEntry("job_id", completed).containsEntry("finding_count", 7).containsEntry("latest_status", "FAILED");
        assertThat(services(target).getFirst()).containsEntry("observation_status", "OBSERVED");
        assertThat(((List<?>) assets.details(target).get("jobs"))).hasSize(20);
        assertThat(((List<?>) assets.list().get("items"))).isNotEmpty();
    }

    @Test
    void upgradeBackfillIsIdempotentAndDoesNotReassessOrReplaceNewerInventory() {
        String target = target();
        String old = queued(target);
        String original = json.writeValueAsString(ServiceDiscoveryTest.host(100, 8080));
        store.jdbc.update("UPDATE scan_job SET status='COMPLETED', report_json=?, finding_count=9 WHERE id=?", original, old);
        bootstrap.run(null);
        String id = Store.string(services(target).getFirst(), "id");
        assets.saveService(target, id, input("보존", false, 0), "admin");
        bootstrap.run(null);
        String latest = complete(target, ServiceDiscoveryTest.host(900, 8080), 2);
        assets.backfill(old);
        assertThat(asset(target)).containsEntry("job_id", latest);
        assertThat(services(target)).hasSize(1);
        assertThat(services(target).getFirst()).containsEntry("id", id).containsEntry("name", "보존").containsEntry("revision", 1);
        assertThat(Store.string(store.one("SELECT report_json FROM scan_job WHERE id=?", old), "report_json")).isEqualTo(original);
    }

    private String target() {
        String id = UUID.randomUUID().toString();
        store.jdbc.update("INSERT INTO scan_target(id, name, host, port, ssh_user, private_key, host_key, description, created_by, created_at) VALUES (?, 'asset fixture', '192.0.2.1', 22, 'observer', '', '', '', 'admin', ?)", id, Instant.now().toString());
        return id;
    }
    private String queued(String target) {
        String job = UUID.randomUUID().toString();
        store.jdbc.update("INSERT INTO scan_job(id, target_id, target_name, requested_by, status, created_at) VALUES (?, ?, 'fixture', 'admin', 'QUEUED', ?)", job, target, Instant.now().toString());
        return job;
    }
    private String complete(String target, Observation report, long count) { String job = queued(target); assets.complete(job, report, count); return job; }
    @SuppressWarnings("unchecked") private Map<String, Object> asset(String target) { return (Map<String, Object>) assets.details(target).get("asset"); }
    @SuppressWarnings("unchecked") private List<Map<String, Object>> services(String target) { return (List<Map<String, Object>>) assets.details(target).get("services"); }
    private Assets.ProfileInput profile(int revision) { return new Assets.ProfileInput("ASSET-01", "PRODUCTION", "플랫폼팀", "IDC", "업무", "ACTIVE", "메모", revision); }
    private Assets.ServiceInput input(String name, boolean archived, int revision) {
        return new Assets.ServiceInput(name, "TCP 8080", "담당파트", "nginx", "확인 필요", "nginx", "MIDDLEWARE", "/srv/app", "/srv/data", "/srv/log",
                "systemctl start example", "systemctl stop example", "systemctl status example", "내부 → 연계 서버", "<script>literal</script>", archived, revision);
    }
}
