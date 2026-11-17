package com.wook.observer.platform;

import com.wook.observer.report.OperatorReport;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

@Service
@Profile("!collector")
public class OfflineImports {
    private final OfflineInventory inventory;
    private final Store store;
    private final Targets targets;
    private final Assessor assessor;
    private final Assets assets;
    private final JsonMapper json = JsonMapper.builder().build();
    public OfflineImports(OfflineInventory inventory, Store store, Targets targets, Assessor assessor, Assets assets) {
        this.inventory = inventory; this.store = store; this.targets = targets; this.assessor = assessor; this.assets = assets;
    }

    public Map<String, Object> preview(byte[] bytes, String target) {
        var parsed = inventory.parse(bytes);
        var report = parsed.report();
        var result = new LinkedHashMap<String, Object>();
        result.put("host", report.host()); result.put("collectedAt", report.collectedAt()); result.put("sha256", parsed.sha256());
        var os = OsLifecycle.identify(report);
        result.put("distribution", os.displayName()); result.put("osSupport", OsLifecycle.evaluate(os));
        result.put("processes", report.processes().size()); result.put("containers", report.containers().size());
        result.put("jvms", report.javaProcesses().size()); result.put("rpmPackages", report.systemInventory().rpm().packages().size());
        result.put("sockets", report.systemInventory().sockets().entries().size()); result.put("issues", report.issues());
        String expected = expectedHost(target);
        result.put("expectedHost", expected);
        result.put("identityMismatch", !expected.isEmpty() && !expected.equalsIgnoreCase(report.host().name()));
        var duplicate = store.rows("SELECT id, target_id FROM scan_job WHERE import_sha256=?", parsed.sha256());
        result.put("duplicate", duplicate.isEmpty() ? Map.of() : duplicate.getFirst());
        OperationLog.info("offline.previewed", "targetId", target.isEmpty() ? "NEW" : target, "bytes", bytes.length,
                "processes", report.processes().size(), "issues", report.issues().size());
        return result;
    }

    /** Validation, assessment and projection either all commit, or leave existing data untouched. */
    @Transactional
    public Map<String, Object> importFile(byte[] bytes, String target, String name, String host, boolean confirmIdentity, String actor) {
        long timer = System.nanoTime();
        var parsed = inventory.parse(bytes);
        // Serialize imports against one stable row, including first-time imports, before duplicate detection.
        // Keep this separate from catalog_state: catalog readers/writers also use a Java monitor.
        store.one("SELECT singleton FROM offline_import_lock WHERE singleton=1 FOR UPDATE");
        var duplicate = store.rows("SELECT id, target_id FROM scan_job WHERE import_sha256=?", parsed.sha256());
        if (!duplicate.isEmpty()) {
            var previous = duplicate.getFirst();
            if (!target.isEmpty() && !target.equals(previous.get("target_id"))) throw new ApiProblem(409, "이 파일은 다른 자산에 이미 반영되었습니다. 반영할 자산을 확인하세요.");
            OperationLog.info("offline.duplicate", "jobId", previous.get("id"), "targetId", previous.get("target_id"));
            return Map.of("id", previous.get("id"), "targetId", previous.get("target_id"), "duplicate", true);
        }
        if (target.isEmpty()) target = targets.saveUpload(null, new Targets.UploadInput(name, host, ""), actor);
        else store.one("SELECT id FROM scan_target WHERE id=? FOR UPDATE", target);
        if (!store.rows("SELECT id FROM scan_job WHERE target_id=? AND status IN ('QUEUED','RUNNING')", target).isEmpty())
            throw new ApiProblem(409, "이 자산은 점검 중입니다. 완료 후 업로드하세요.");
        String expected = expectedHost(target);
        if (!expected.isEmpty() && !expected.equalsIgnoreCase(parsed.report().host().name()) && !confirmIdentity)
            throw new ApiProblem(409, "이전 수집 호스트명과 다릅니다. 같은 서버인지 확인한 뒤 반영하세요.");
        var report = assessor.assess(parsed.report());
        String id = UUID.randomUUID().toString();
        var row = store.one("SELECT name FROM scan_target WHERE id=?", target);
        store.jdbc.update("INSERT INTO scan_job(id, target_id, target_name, requested_by, status, created_at, collection_method, import_sha256) VALUES (?, ?, ?, ?, 'RUNNING', ?, 'UPLOAD', ?)",
                id, target, row.get("name"), actor, Instant.now().toString(), parsed.sha256());
        assets.complete(id, report, OperatorReport.from(report).actions().size());
        boolean current = id.equals(store.one("SELECT job_id FROM asset_inventory WHERE target_id=?", target).get("job_id"));
        OperationLog.afterCommit("offline.imported", "targetId", target, "jobId", id, "actor", actor, "sha256", parsed.sha256(),
                "bytes", bytes.length, "currentInventory", current, "durationMs", OperationLog.elapsed(timer));
        return Map.of("id", id, "targetId", target, "duplicate", false, "currentInventory", current);
    }

    private String expectedHost(String target) {
        if (target.isEmpty()) return "";
        store.one("SELECT id FROM scan_target WHERE id=?", target);
        var current = store.rows("SELECT summary_json FROM asset_inventory WHERE target_id=?", target);
        return current.isEmpty() ? "" : json.readValue(Store.string(current.getFirst(), "summary_json"), ServiceDiscovery.Summary.class).host().name();
    }
}
