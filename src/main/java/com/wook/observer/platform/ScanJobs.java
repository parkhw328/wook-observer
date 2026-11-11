package com.wook.observer.platform;

import com.wook.observer.model.Observation;
import jakarta.annotation.PreDestroy;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

@Service
@Profile("!collector")
public class ScanJobs implements ApplicationRunner {
    private final Store store;
    private final Targets targets;
    private final SshCollector collector;
    private final Assessor assessor;
    private final JsonMapper json = JsonMapper.builder().build();
    private final ThreadPoolExecutor executor = new ThreadPoolExecutor(2, 2, 0, TimeUnit.SECONDS, new ArrayBlockingQueue<>(16));
    public ScanJobs(Store store, Targets targets, SshCollector collector, Assessor assessor) {
        this.store = store; this.targets = targets; this.collector = collector; this.assessor = assessor;
    }

    @Override
    public void run(ApplicationArguments args) {
        store.jdbc.update("UPDATE scan_job SET status = 'FAILED', finished_at = ?, message = ? WHERE status IN ('QUEUED', 'RUNNING')",
                Instant.now().toString(), "서비스가 재시작되어 완료되지 못했습니다. 다시 점검하세요.");
    }

    public List<Map<String, Object>> list() {
        return store.rows("SELECT id, target_id, target_name, requested_by, status, created_at, finished_at, message, finding_count FROM scan_job ORDER BY created_at DESC LIMIT 100");
    }

    public synchronized String start(String targetId, String actor) {
        var target = targets.credentials(targetId);
        if (!store.rows("SELECT id FROM scan_job WHERE target_id = ? AND status IN ('QUEUED', 'RUNNING')", targetId).isEmpty()) {
            throw new ApiProblem(409, "이 대상은 이미 점검 중입니다.");
        }
        String id = UUID.randomUUID().toString();
        store.jdbc.update("INSERT INTO scan_job(id, target_id, target_name, requested_by, status, created_at) VALUES (?, ?, ?, ?, 'QUEUED', ?)",
                id, targetId, Store.string(target, "name"), actor, Instant.now().toString());
        try { executor.execute(() -> execute(id, target)); }
        catch (RejectedExecutionException e) {
            fail(id, "점검 대기열이 가득 찼습니다. 잠시 후 다시 시도하세요.");
            throw new ApiProblem(429, "점검 대기열이 가득 찼습니다.");
        }
        return id;
    }

    private void execute(String id, Map<String, Object> target) {
        try {
            store.jdbc.update("UPDATE scan_job SET status = 'RUNNING' WHERE id = ?", id);
            Observation report = assessor.assess(collector.collect(target));
            long count = report.assessment().findings().stream().filter(f -> "발견".equals(f.status()) || "의심".equals(f.status())).count();
            store.jdbc.update("UPDATE scan_job SET status = 'COMPLETED', finished_at = ?, report_json = ?, finding_count = ?, message = ? WHERE id = ?",
                    Instant.now().toString(), json.writeValueAsString(report), count, "수집 완료; 수집 한계와 취약점 판정 범위를 확인하세요.", id);
        } catch (Exception e) { fail(id, e instanceof ApiProblem ? e.getMessage() : "점검 실패. SSH 연결·대상 도구·접근 권한을 확인하세요."); }
    }

    private void fail(String id, String message) {
        store.jdbc.update("UPDATE scan_job SET status = 'FAILED', finished_at = ?, message = ? WHERE id = ?", Instant.now().toString(), message, id);
    }

    public Observation report(String id) {
        var row = store.one("SELECT status, report_json FROM scan_job WHERE id = ?", id);
        if (!"COMPLETED".equals(row.get("status"))) { throw new ApiProblem(409, "완료된 점검만 보고서를 받을 수 있습니다."); }
        return json.readValue(Store.string(row, "report_json"), Observation.class);
    }

    @PreDestroy
    public void close() { executor.shutdownNow(); }
}
