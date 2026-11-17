package com.wook.observer.platform;

import com.wook.observer.model.Observation;
import com.wook.observer.report.AssetWorkbook;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Isolation;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.io.IOException;
import java.io.OutputStream;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
@Profile("!collector")
public class Assets {
    private final Store store;
    private final AssetWorkbook workbook;
    private final JsonMapper json = JsonMapper.builder().build();
    private static final String ASSET_COLUMNS = """
            SELECT t.id, t.name, t.host, t.port, t.description, t.collection_mode,
                p.asset_code, p.environment, p.owner, p.location, p.purpose, p.lifecycle, p.notes,
                COALESCE(p.revision, 0) AS revision, p.updated_by, p.updated_at, p.els_contract, p.els_valid_until, p.els_confirmed_by, p.els_confirmed_at,
                i.job_id, i.observed_at, i.summary_json, j.finding_count, j.collection_method, j.import_sha256,
                a.id AS latest_job_id, a.status AS latest_status, a.created_at AS latest_attempt_at, a.message AS latest_message,
                (SELECT COUNT(*) FROM asset_service s WHERE s.target_id=t.id AND s.archived=FALSE) AS service_count,
                (SELECT COUNT(*) FROM asset_service s WHERE s.target_id=t.id AND s.archived=FALSE
                    AND (s.start_command='' OR s.stop_command='' OR s.status_command='')) AS undocumented_count
            FROM scan_target t
            LEFT JOIN asset_profile p ON p.target_id=t.id
            LEFT JOIN asset_inventory i ON i.target_id=t.id
            LEFT JOIN scan_job j ON j.id=i.job_id
            LEFT JOIN scan_job a ON a.id=(SELECT z.id FROM scan_job z WHERE z.target_id=t.id ORDER BY z.created_at DESC, z.id DESC LIMIT 1)
            """;

    public Assets(Store store, AssetWorkbook workbook) { this.store = store; this.workbook = workbook; }

    public Map<String, Object> list() {
        var rows = store.rows(ASSET_COLUMNS + " ORDER BY t.name, t.id").stream().map(this::asset).toList();
        return Map.of("items", rows, "summary", Map.of(
                "total", rows.size(),
                "unscanned", rows.stream().filter(row -> row.get("job_id") == null).count(),
                "inProgress", rows.stream().filter(row -> Set.of("RUNNING", "QUEUED").contains(Store.string(row, "latest_status"))).count(),
                "failed", rows.stream().filter(row -> "FAILED".equals(row.get("latest_status"))).count(),
                "osUpgrade", rows.stream().filter(row -> ((OsLifecycle.Support) row.get("os_support")).upgradeRequired()).count(),
                "services", rows.stream().mapToLong(row -> ((Number) row.get("service_count")).longValue()).sum(),
                "undocumented", rows.stream().mapToLong(row -> ((Number) row.get("undocumented_count")).longValue()).sum(),
                "findings", rows.stream().filter(row -> row.get("finding_count") != null).mapToLong(row -> ((Number) row.get("finding_count")).longValue()).sum()));
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Map<String, Object> details(String target) {
        var row = asset(store.one(ASSET_COLUMNS + " WHERE t.id=?", target));
        return Map.of("asset", row, "services", services(row), "network", network(row), "jobs", store.rows(
                "SELECT id, target_id, target_name, requested_by, status, created_at, finished_at, message, finding_count, collection_method FROM scan_job WHERE target_id=? ORDER BY created_at DESC, id DESC LIMIT 20", target));
    }

    private com.wook.observer.model.NetworkObservation network(Map<String, Object> row) {
        String job = Store.string(row, "job_id");
        var report = job.isEmpty() ? null : json.readValue(Store.string(store.one("SELECT report_json FROM scan_job WHERE id=?", job), "report_json"), Observation.class);
        return com.wook.observer.model.NetworkObservation.from(report);
    }

    /** Export saved asset data in one consistent read, without SSH access or reassessment. */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public void export(String target, OutputStream output) throws IOException {
        var rows = target == null ? store.rows(ASSET_COLUMNS + " ORDER BY t.name, t.id")
                : List.of(store.one(ASSET_COLUMNS + " WHERE t.id=?", target));
        workbook.write(rows.stream().map(this::asset).toList(), this::services, this::network, output);
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public String markdown(String target) {
        var rows = target == null ? store.rows(ASSET_COLUMNS + " ORDER BY t.name, t.id")
                : List.of(store.one(ASSET_COLUMNS + " WHERE t.id=?", target));
        return com.wook.observer.report.AssetMarkdown.write(rows.stream().map(this::asset).toList(), this::services, this::network);
    }

    private List<Map<String, Object>> services(Map<String, Object> row) {
        var services = store.rows("SELECT * FROM asset_service WHERE target_id=? ORDER BY archived, source, id", row.get("id"));
        services.forEach(service -> {
            String observed = Store.string(service, "observed_json");
            service.remove("observed_json");
            service.remove("source_key");
            service.put("observed", observed.isEmpty() ? null : json.readValue(observed, ServiceDiscovery.Candidate.class));
            service.put("observation_status", "MANUAL".equals(service.get("source")) ? "MANUAL"
                    : service.get("last_seen_job_id").equals(row.get("job_id")) ? "OBSERVED" : "NOT_OBSERVED");
        });
        return services;
    }

    private Map<String, Object> asset(Map<String, Object> row) {
        String summary = Store.string(row, "summary_json");
        row.remove("summary_json");
        var inventory = summary.isEmpty() ? null : json.readValue(summary, ServiceDiscovery.Summary.class);
        row.put("inventory", inventory);
        row.put("os_support", OsLifecycle.forAsset(inventory, row));
        return row;
    }

    /** Enrich only the current projection; never reassess or rewrite a saved inspection report. */
    public int enrichOsIdentity() {
        int changed = 0;
        for (var row : store.rows("SELECT target_id, job_id, summary_json FROM asset_inventory")) {
            try {
                String saved = Store.string(row, "summary_json");
                var summary = json.readValue(saved, ServiceDiscovery.Summary.class);
                if (summary.osIdentity() != null && !(summary.osIdentity().id().equals("rhel") && summary.osIdentity().version().equals("7"))) continue;
                var report = json.readValue(Store.string(store.one("SELECT report_json FROM scan_job WHERE id=?", row.get("job_id")), "report_json"), Observation.class);
                var identity = OsLifecycle.identify(report);
                if (identity.equals(summary.osIdentity())) continue;
                var enriched = new ServiceDiscovery.Summary(summary.host(), identity.displayName(), summary.socketStatus(), summary.socketMessage(),
                        summary.processCount(), summary.containerCount(), summary.issueCount(), identity);
                changed += store.jdbc.update("UPDATE asset_inventory SET summary_json=? WHERE target_id=? AND job_id=? AND summary_json=?",
                        json.writeValueAsString(enriched), row.get("target_id"), row.get("job_id"), saved);
            } catch (RuntimeException failure) {
                OperationLog.warn("asset.os_identity.unavailable", "targetId", row.get("target_id"), "errorType", failure.getClass().getSimpleName());
            }
        }
        return changed;
    }

    @Transactional
    public void saveProfile(String target, ProfileInput input, String actor) {
        lockTarget(target);
        var existing = store.rows("SELECT revision, els_contract, els_valid_until, els_confirmed_by, els_confirmed_at FROM asset_profile WHERE target_id=?", target);
        int revision = existing.isEmpty() ? 0 : ((Number) existing.getFirst().get("revision")).intValue();
        checkRevision(input.revision(), revision);
        String code = field(input.assetCode(), 120), owner = field(input.owner(), 120), location = field(input.location(), 200);
        String purpose = field(input.purpose(), 1000), notes = field(input.notes(), 4000);
        choice(input.environment(), Set.of("UNSPECIFIED", "PRODUCTION", "STAGING", "DEVELOPMENT", "TEST"));
        choice(input.lifecycle(), Set.of("ACTIVE", "MAINTENANCE", "RETIRED"));
        if (existing.isEmpty()) {
            store.jdbc.update("INSERT INTO asset_profile(target_id, asset_code, environment, owner, location, purpose, lifecycle, notes, revision, updated_by, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, 1, ?, ?)",
                    target, code, input.environment(), owner, location, purpose, input.lifecycle(), notes, actor, Instant.now().toString());
        } else {
            store.jdbc.update("UPDATE asset_profile SET asset_code=?, environment=?, owner=?, location=?, purpose=?, lifecycle=?, notes=?, revision=revision+1, updated_by=?, updated_at=? WHERE target_id=?",
                    code, input.environment(), owner, location, purpose, input.lifecycle(), notes, actor, Instant.now().toString(), target);
        }
        // Omitted fields from older clients preserve the recorded contract; only an explicit checkbox value changes it.
        if (input.elsContract() != null) {
            String until = input.elsContract() ? field(input.elsValidUntil(), 10) : "";
            if (input.elsContract()) {
                try {
                    var end = java.time.LocalDate.parse(until);
                    if (!end.toString().equals(until) || end.isBefore(java.time.LocalDate.of(2024, 7, 1)) || end.isAfter(java.time.LocalDate.of(2029, 5, 31))) throw new IllegalArgumentException();
                } catch (RuntimeException invalid) { throw new ApiProblem(400, "ELS 계약 종료일을 입력하세요 (2024-07-01 ~ 2029-05-31)."); }
            }
            boolean changed = existing.isEmpty() ? input.elsContract() : !input.elsContract().equals(existing.getFirst().get("els_contract"))
                    || !until.equals(Store.string(existing.getFirst(), "els_valid_until"));
            if (changed) store.jdbc.update("UPDATE asset_profile SET els_contract=?, els_valid_until=?, els_confirmed_by=?, els_confirmed_at=? WHERE target_id=?",
                    input.elsContract(), until, input.elsContract() ? actor : "", input.elsContract() ? Instant.now().toString() : "", target);
        }
        OperationLog.afterCommit("asset.profile.saved", "targetId", target, "actor", actor, "revision", revision + 1);
    }

    @Transactional
    public String saveService(String target, String id, ServiceInput input, String actor) {
        lockTarget(target);
        String name = field(input.name(), 160), bindings = field(input.bindings(), 2000), owner = field(input.owner(), 120);
        String start = field(input.startCommand(), 4000), stop = field(input.stopCommand(), 4000), notes = field(input.notes(), 4000);
        String software = field(input.software(), 200), version = field(input.softwareVersion(), 200), runUser = field(input.runUser(), 120);
        String installPath = field(input.installPath(), 2000), dataPath = field(input.dataPath(), 2000), logPath = field(input.logPath(), 2000);
        String status = field(input.statusCommand(), 4000), dependencies = field(input.dependencies(), 4000);
        choice(input.serviceType(), Set.of("MIDDLEWARE", "APPLICATION", "COMMON", "OTHER", "WEB", "DATABASE", "CONTAINER", "CACHE", "MESSAGING", "INFRASTRUCTURE"));
        boolean create = id == null;
        if (create) {
            checkRevision(input.revision(), 0);
            if (name.isBlank()) throw new ApiProblem(400, "수동 서비스 이름을 입력하세요.");
            id = UUID.randomUUID().toString();
            store.jdbc.update("INSERT INTO asset_service(id, target_id, source_key, source) VALUES (?, ?, ?, 'MANUAL')", id, target, "manual:" + id);
        } else {
            var row = store.one("SELECT revision, source FROM asset_service WHERE id=? AND target_id=?", id, target);
            checkRevision(input.revision(), ((Number) row.get("revision")).intValue());
            if ("MANUAL".equals(row.get("source")) && name.isBlank()) throw new ApiProblem(400, "수동 서비스 이름을 입력하세요.");
        }
        store.jdbc.update("""
                UPDATE asset_service SET name=?, bindings=?, owner=?, software=?, software_version=?, run_user=?, service_type=?,
                    install_path=?, data_path=?, log_path=?, start_command=?, stop_command=?, status_command=?, dependencies=?,
                    notes=?, archived=?, revision=revision+1, updated_by=?, updated_at=? WHERE id=? AND target_id=?
                """, name, bindings, owner, software, version, runUser, input.serviceType(), installPath, dataPath, logPath, start, stop, status,
                dependencies, notes, input.archived(), actor, Instant.now().toString(), id, target);
        OperationLog.afterCommit("asset.service.saved", "targetId", target, "serviceId", id, "actor", actor,
                "operation", create ? "CREATE" : "UPDATE", "archived", input.archived(), "revision", input.revision() + 1);
        return id;
    }

    /** A completed report and its asset projection commit together, preserving all handwritten fields. */
    @Transactional
    public void complete(String job, Observation report, long findings) {
        var row = store.one("SELECT target_id, created_at FROM scan_job WHERE id=?", job);
        lockTarget(Store.string(row, "target_id"));
        store.jdbc.update("UPDATE scan_job SET status='COMPLETED', finished_at=?, report_json=?, finding_count=?, message=? WHERE id=?",
                Instant.now().toString(), json.writeValueAsString(report), findings, "수집 완료; 수집 한계와 취약점 판정 범위를 확인하세요.", job);
        project(job, row, report);
    }

    @Transactional
    public void backfill(String job) {
        var row = store.one("SELECT target_id, created_at, report_json FROM scan_job WHERE id=? AND status='COMPLETED'", job);
        lockTarget(Store.string(row, "target_id"));
        project(job, row, json.readValue(Store.string(row, "report_json"), Observation.class));
    }

    private void project(String job, Map<String, Object> scan, Observation report) {
        String target = Store.string(scan, "target_id");
        var current = store.rows("SELECT i.job_id, i.observed_at FROM asset_inventory i WHERE i.target_id=?", target);
        if (!current.isEmpty() && (job.equals(current.getFirst().get("job_id"))
                || Instant.parse(Store.string(current.getFirst(), "observed_at")).isAfter(report.collectedAt()))) return;
        String observedAt = report.collectedAt().toString();
        for (var candidate : ServiceDiscovery.discover(report)) {
            String observed = json.writeValueAsString(candidate);
            int updated = store.jdbc.update("UPDATE asset_service SET observed_json=?, last_seen_job_id=?, last_seen_at=? WHERE target_id=? AND source_key=? AND source='AUTO'",
                    observed, job, observedAt, target, candidate.key());
            if (updated == 0) store.jdbc.update("INSERT INTO asset_service(id, target_id, source_key, source, observed_json, last_seen_job_id, last_seen_at) VALUES (?, ?, ?, 'AUTO', ?, ?, ?)",
                    UUID.randomUUID().toString(), target, candidate.key(), observed, job, observedAt);
        }
        String summary = json.writeValueAsString(ServiceDiscovery.summary(report));
        if (current.isEmpty()) store.jdbc.update("INSERT INTO asset_inventory(target_id, job_id, observed_at, summary_json) VALUES (?, ?, ?, ?)", target, job, observedAt, summary);
        else store.jdbc.update("UPDATE asset_inventory SET job_id=?, observed_at=?, summary_json=? WHERE target_id=?", job, observedAt, summary, target);
    }

    private void lockTarget(String target) { store.one("SELECT id FROM scan_target WHERE id=? FOR UPDATE", target); }
    private static void checkRevision(Integer given, int actual) {
        if (given == null || given != actual) throw new ApiProblem(409, "다른 사용자가 수정했습니다. 입력 내용을 복사한 뒤 새로고침하고 다시 저장하세요.");
    }
    private static String field(String value, int max) {
        if (value == null || value.length() > max || value.codePoints().anyMatch(c -> c < 32 && c != '\n' && c != '\t' && c != '\r' || c == 127))
            throw new ApiProblem(400, "입력값과 최대 길이를 확인하세요. 허용하지 않는 제어 문자는 저장할 수 없습니다.");
        return value.strip();
    }
    private static void choice(String value, Set<String> choices) {
        if (value == null || !choices.contains(value)) throw new ApiProblem(400, "지원하는 환경·운영 상태를 선택하세요.");
    }
    public record ProfileInput(String assetCode, String environment, String owner, String location, String purpose,
                               String lifecycle, String notes, Integer revision, Boolean elsContract, String elsValidUntil) {
        public ProfileInput(String assetCode, String environment, String owner, String location, String purpose, String lifecycle, String notes, Integer revision) {
            this(assetCode, environment, owner, location, purpose, lifecycle, notes, revision, null, null);
        }
    }
    public record ServiceInput(String name, String bindings, String owner, String software, String softwareVersion,
                               String runUser, String serviceType, String installPath, String dataPath, String logPath, String startCommand,
                               String stopCommand, String statusCommand, String dependencies, String notes,
                               boolean archived, Integer revision) { }
}
