package com.wook.observer.platform;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/** Reversible ledger removal. Observations, credentials and handwritten data are never erased. */
@Service
@Profile("!collector")
public class AssetTrash {
    private final Store store;
    private final JsonMapper json = JsonMapper.builder().build();
    public AssetTrash(Store store) { this.store = store; }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Map<String, Object> list() {
        var assets = store.rows("""
                SELECT t.id, t.name, t.host, t.collection_mode, t.trash_revision, t.deleted_by, t.deleted_at,
                    p.asset_code, p.owner,
                    (SELECT COUNT(*) FROM asset_service s WHERE s.target_id=t.id) AS service_count,
                    (SELECT COUNT(*) FROM asset_service s WHERE s.target_id=t.id AND s.deleted=TRUE) AS deleted_service_count,
                    (SELECT COUNT(*) FROM scan_job j WHERE j.target_id=t.id) AS job_count
                FROM scan_target t LEFT JOIN asset_profile p ON p.target_id=t.id
                WHERE t.deleted=TRUE ORDER BY t.deleted_at DESC, t.id
                """);
        // Services of a deleted asset stay with their parent. Restore the parent before individual services.
        var services = store.rows("""
                SELECT s.id, s.target_id, s.name, s.observed_json, s.source, s.archived, s.revision,
                    s.deleted_by, s.deleted_at, t.name AS target_name, t.host
                FROM asset_service s JOIN scan_target t ON t.id=s.target_id
                WHERE s.deleted=TRUE AND t.deleted=FALSE ORDER BY s.deleted_at DESC, s.id
                """);
        services.forEach(row -> {
            row.put("name", serviceName(row));
            row.remove("observed_json");
        });
        return Map.of("assets", assets, "services", services);
    }

    @Transactional
    public void deleteAsset(String target, AssetInput input, String actor) {
        var row = store.one("SELECT id, name, deleted, trash_revision FROM scan_target WHERE id=? FOR UPDATE", target);
        checkState(row, false, input.revision(), "trash_revision");
        if (!Store.string(row, "name").equals(input.name())) throw new ApiProblem(409, "자산 이름이 일치하지 않습니다. 현재 이름을 확인한 뒤 다시 입력하세요.");
        if (!store.rows("SELECT id FROM scan_job WHERE target_id=? AND status IN ('QUEUED','RUNNING')", target).isEmpty())
            throw new ApiProblem(409, "점검 중인 자산은 삭제할 수 없습니다. 점검이 끝난 뒤 다시 시도하세요.");
        store.jdbc.update("UPDATE scan_target SET deleted=TRUE, trash_revision=trash_revision+1, deleted_by=?, deleted_at=? WHERE id=?",
                actor, Instant.now().toString(), target);
        record(target, null, Store.string(row, "name"), "ASSET_DELETED", actor);
    }

    @Transactional
    public void restoreAsset(String target, RevisionInput input, String actor) {
        var row = store.one("SELECT id, name, deleted, trash_revision FROM scan_target WHERE id=? FOR UPDATE", target);
        checkState(row, true, input.revision(), "trash_revision");
        store.jdbc.update("UPDATE scan_target SET deleted=FALSE, trash_revision=trash_revision+1, deleted_by=NULL, deleted_at=NULL WHERE id=?", target);
        record(target, null, Store.string(row, "name"), "ASSET_RESTORED", actor);
    }

    @Transactional
    public void deleteService(String target, String service, RevisionInput input, String actor) {
        changeService(target, service, input, actor, true);
    }

    @Transactional
    public void restoreService(String target, String service, RevisionInput input, String actor) {
        changeService(target, service, input, actor, false);
    }

    private void changeService(String target, String service, RevisionInput input, String actor, boolean delete) {
        requireActive(store, target, true);
        var row = store.one("SELECT id, name, observed_json, deleted, revision FROM asset_service WHERE target_id=? AND id=?", target, service);
        checkState(row, !delete, input.revision(), "revision");
        store.jdbc.update("UPDATE asset_service SET deleted=?, revision=revision+1, deleted_by=?, deleted_at=? WHERE target_id=? AND id=?",
                delete, delete ? actor : null, delete ? Instant.now().toString() : null, target, service);
        record(target, service, serviceName(row), delete ? "SERVICE_DELETED" : "SERVICE_RESTORED", actor);
    }

    /** Every mutation/scan/import uses the same parent row lock as deletion. */
    static void requireActive(Store store, String target, boolean lock) {
        var row = store.one("SELECT deleted FROM scan_target WHERE id=?" + (lock ? " FOR UPDATE" : ""), target);
        if (Boolean.TRUE.equals(row.get("deleted"))) throw new ApiProblem(409, "휴지통에 있는 자산입니다. 관리자가 자산 휴지통에서 복원한 뒤 다시 시도하세요.");
    }

    private static void checkState(Map<String, Object> row, boolean deleted, Integer revision, String column) {
        if (!Boolean.valueOf(deleted).equals(row.get("deleted")) || revision == null || revision != ((Number) row.get(column)).intValue())
            throw new ApiProblem(409, "항목이 이미 변경되었습니다. 새로고침한 뒤 다시 시도하세요.");
    }

    private String serviceName(Map<String, Object> row) {
        String name = Store.string(row, "name"), observed = Store.string(row, "observed_json");
        return !name.isEmpty() ? name : observed.isEmpty() ? "이름 미입력" : json.readValue(observed, ServiceDiscovery.Candidate.class).name();
    }

    private void record(String target, String service, String name, String action, String actor) {
        store.jdbc.update("INSERT INTO asset_management_event(id, target_id, service_id, item_name, action, actor, occurred_at) VALUES (?, ?, ?, ?, ?, ?, ?)",
                UUID.randomUUID().toString(), target, service, name.substring(0, Math.min(name.length(), 160)), action, actor, Instant.now().toString());
        OperationLog.afterCommit("asset.trash.changed", "targetId", target, "serviceId", service, "actor", actor, "action", action);
    }

    public record AssetInput(String name, Integer revision) { }
    public record RevisionInput(Integer revision) { }
}
