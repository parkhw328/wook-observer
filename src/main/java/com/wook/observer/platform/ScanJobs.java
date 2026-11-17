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
import java.util.LinkedHashMap;
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
    private final Assets assets;
    private final JsonMapper json = JsonMapper.builder().build();
    private final ThreadPoolExecutor executor = new ThreadPoolExecutor(2, 2, 0, TimeUnit.SECONDS, new ArrayBlockingQueue<>(16));
    public ScanJobs(Store store, Targets targets, SshCollector collector, Assessor assessor, Assets assets) {
        this.store = store; this.targets = targets; this.collector = collector; this.assessor = assessor; this.assets = assets;
    }

    @Override
    public void run(ApplicationArguments args) {
        int interrupted = store.jdbc.update("UPDATE scan_job SET status = 'FAILED', finished_at = ?, message = ? WHERE status IN ('QUEUED', 'RUNNING')",
                Instant.now().toString(), "서비스가 재시작되어 완료되지 못했습니다. 다시 점검하세요.");
        OperationLog.info("scan.recovery", "interruptedJobs", interrupted);
    }

    public List<Map<String, Object>> list() {
        return store.rows("SELECT id, target_id, target_name, requested_by, status, created_at, finished_at, message, finding_count, collection_method FROM scan_job ORDER BY created_at DESC LIMIT 100");
    }

    public synchronized String start(String targetId, String actor) {
        var target = targets.credentials(targetId);
        if ("UPLOAD".equals(target.get("collection_mode"))) throw new ApiProblem(400, "파일 업로드 자산입니다. 서버에서 수집 스크립트를 실행한 결과를 업로드하세요.");
        if (!store.rows("SELECT id FROM scan_job WHERE target_id = ? AND status IN ('QUEUED', 'RUNNING')", targetId).isEmpty()) {
            OperationLog.warn("scan.duplicate", "targetId", targetId);
            throw new ApiProblem(409, "이 대상은 이미 점검 중입니다.");
        }
        String id = UUID.randomUUID().toString();
        store.jdbc.update("INSERT INTO scan_job(id, target_id, target_name, requested_by, status, created_at) VALUES (?, ?, ?, ?, 'QUEUED', ?)",
                id, targetId, Store.string(target, "name"), actor, Instant.now().toString());
        var context = new LinkedHashMap<String, String>();
        var caller = org.slf4j.MDC.getCopyOfContextMap();
        if (caller != null) context.putAll(caller);
        context.put("actor", actor); context.put("jobId", id); context.put("targetId", targetId);
        long queued = System.nanoTime();
        OperationLog.info("scan.queued", "jobId", id, "targetId", targetId, "host", target.get("host"), "queueSize", executor.getQueue().size());
        try { executor.execute(() -> { try (var ignored = OperationLog.context(context)) { execute(id, target, queued); } }); }
        catch (RejectedExecutionException e) {
            OperationLog.warn("scan.queue_full", "jobId", id, "targetId", targetId, "queueSize", executor.getQueue().size());
            fail(id, "점검 대기열이 가득 찼습니다. 잠시 후 다시 시도하세요.");
            throw new ApiProblem(429, "점검 대기열이 가득 찼습니다.");
        }
        return id;
    }

    private void execute(String id, Map<String, Object> target, long queued) {
        long started = System.nanoTime();
        String phase = "COLLECTION";
        try {
            store.jdbc.update("UPDATE scan_job SET status = 'RUNNING' WHERE id = ?", id);
            OperationLog.info("scan.started", "host", target.get("host"), "queueWaitMs", OperationLog.elapsed(queued));
            Observation report = collector.collect(target);
            OperationLog.info("scan.collected", "durationMs", OperationLog.elapsed(started), "processes", report.processes().size(),
                    "jvms", report.javaProcesses().size(), "containers", report.containers().size(), "issues", report.issues().size());
            phase = "ASSESSMENT";
            long assessment = System.nanoTime();
            OperationLog.info("scan.assessment.started");
            report = assessor.assess(report);
            long count = com.wook.observer.report.OperatorReport.from(report).actions().size();
            OperationLog.info("scan.assessed", "durationMs", OperationLog.elapsed(assessment), "findings", count,
                    "catalogRevision", report.assessment() == null ? "none" : report.assessment().catalogRevision());
            phase = "PERSISTENCE";
            assets.complete(id, report, count);
            OperationLog.info("scan.completed", "durationMs", OperationLog.elapsed(started), "findings", count);
        } catch (Exception e) {
            OperationLog.failure("scan.failed", e, "phase", phase, "durationMs", OperationLog.elapsed(started));
            fail(id, e instanceof ApiProblem ? e.getMessage() : "점검 실패. SSH 연결·대상 도구·접근 권한을 확인하세요.");
        }
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
    public void close() {
        OperationLog.info("scan.shutdown", "active", executor.getActiveCount(), "queued", executor.getQueue().size());
        executor.shutdownNow();
    }
}
