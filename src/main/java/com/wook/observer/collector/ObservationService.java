package com.wook.observer.collector;

import com.wook.observer.model.Observation;
import com.wook.observer.model.Observation.Host;
import com.wook.observer.model.Observation.Issue;
import com.wook.observer.model.Observation.ProcessInfo;
import org.springframework.boot.info.BuildProperties;
import org.springframework.stereotype.Service;

import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.locks.ReentrantLock;

@Service
public class ObservationService {
    private final DockerCollector docker;
    private final ProcessCollector processes;
    private final JavaCollector java;
    private final BuildProperties build;
    private final ReentrantLock scanLock = new ReentrantLock();

    public ObservationService(DockerCollector docker, ProcessCollector processes, JavaCollector java, BuildProperties build) {
        this.docker = docker;
        this.processes = processes;
        this.java = java;
        this.build = build;
    }

    public Observation collect() {
        if (!scanLock.tryLock()) { throw new ScanInProgressException(); }
        try {
            Instant collectedAt = Instant.now();
            List<Issue> issues = new ArrayList<>();
            if (!System.getProperty("os.name").equalsIgnoreCase("Linux")) {
                issues.add(new Issue("host", "platform", "현재 버전은 Linux 우선 지원: /proc 기반 상세 수집 미지원"));
            }
            DockerCollector.Result containerResult;
            try {
                containerResult = docker.collect(issues);
            } catch (Exception exception) {
                issues.add(new Issue("docker", "engine", "Docker 수집 실패 (" + exception.getClass().getSimpleName() + ")"));
                containerResult = new DockerCollector.Result("미확인", List.of());
            }
            List<ProcessInfo> processResult;
            try {
                processResult = processes.collect(containerResult.containers(), issues);
            } catch (Exception exception) {
                issues.add(new Issue("process", "host", "프로세스 목록 조회 실패"));
                processResult = List.of();
            }
            var javaResult = java.collect(processResult, issues);
            return new Observation(build.getVersion(), collectedAt, host(containerResult.engineVersion()),
                    processResult, javaResult, containerResult.containers(), List.copyOf(issues));
        } finally {
            scanLock.unlock();
        }
    }

    private Host host(String dockerVersion) {
        String hostname = "미확인";
        try { hostname = Files.readString(Path.of("/etc/hostname")).strip(); }
        catch (Exception ignored) { /* Hostname is optional metadata. */ }
        var operatingSystem = ManagementFactory.getOperatingSystemMXBean();
        long memory = operatingSystem instanceof com.sun.management.OperatingSystemMXBean extended
                ? extended.getTotalMemorySize() : -1;
        String scope = DockerCollector.insideContainer()
                ? "수집기 컨테이너의 PID/자원 네임스페이스; 전체 호스트 범위 아님"
                : "현재 OS/PID 네임스페이스에서 접근 가능한 범위";
        return new Host(hostname, operatingSystem.getName(), operatingSystem.getVersion(),
                operatingSystem.getArch(), operatingSystem.getAvailableProcessors(), memory, scope, dockerVersion);
    }

    public static class ScanInProgressException extends RuntimeException {
        public ScanInProgressException() { super("이미 분석이 진행 중입니다. 완료 후 다시 요청하세요."); }
    }
}
