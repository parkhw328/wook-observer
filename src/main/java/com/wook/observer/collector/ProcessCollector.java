package com.wook.observer.collector;

import com.wook.observer.config.ObserverProperties;
import com.wook.observer.model.Observation.ContainerInfo;
import com.wook.observer.model.Observation.Issue;
import com.wook.observer.model.Observation.ProcessInfo;
import com.wook.observer.model.ServiceCategory;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Component
public class ProcessCollector {
    private final ObserverProperties properties;
    private final ServiceClassifier classifier;

    public ProcessCollector(ObserverProperties properties, ServiceClassifier classifier) {
        this.properties = properties;
        this.classifier = classifier;
    }

    public List<ProcessInfo> collect(List<ContainerInfo> containers, List<Issue> issues) {
        Map<Long, ContainerInfo> byPid = new HashMap<>();
        containers.forEach(container -> container.processIds().forEach(pid -> byPid.put(pid, container)));
        List<ProcessHandle> handles;
        try (var stream = ProcessHandle.allProcesses()) {
            handles = stream.sorted(Comparator.comparingLong(ProcessHandle::pid)).toList();
        }
        if (handles.size() > properties.getMaxProcesses()) {
            issues.add(new Issue("process", "host", "프로세스 수 제한으로 일부 생략: " + handles.size()));
        }
        List<ProcessInfo> result = new ArrayList<>();
        for (ProcessHandle handle : handles.stream().limit(properties.getMaxProcesses()).toList()) {
            try {
                var info = handle.info();
                String executable = info.command().orElse("");
                String name = executable.isEmpty() ? readProc(handle.pid(), "comm").strip()
                        : Path.of(executable).getFileName().toString();
                if (name.isBlank()) {
                    name = "미확인";
                    issues.add(new Issue("process", Long.toString(handle.pid()), "실행 파일 조회 불가: 권한 부족 또는 프로세스 종료"));
                }
                var category = classifier.classify("pid:" + handle.pid(), name);
                ContainerInfo container = byPid.get(handle.pid());
                if (container != null && container.category() != ServiceCategory.OTHER
                        && !properties.getServiceOverrides().containsKey("pid:" + handle.pid())) {
                    category = new ServiceClassifier.Classification(container.category(), "소속 컨테이너: " + container.name());
                }
                Map<String, String> status = parseStatus(readProc(handle.pid(), "status"));
                if (status.isEmpty()) {
                    issues.add(new Issue("process", Long.toString(handle.pid()), "/proc 상태 읽기 불가: 메모리·스레드 미확인"));
                }
                result.add(new ProcessInfo(handle.pid(), handle.parent().map(ProcessHandle::pid).orElse(0L),
                        name, executable, info.user().orElse("미확인"), info.startInstant().orElse(null),
                        info.totalCpuDuration().map(java.time.Duration::toMillis).orElse(null),
                        number(status.get("VmRSS"), 1024L), integer(status.get("Threads")), category.category(),
                        category.evidence(), container == null ? "" : container.id()));
            } catch (Exception exception) {
                issues.add(new Issue("process", Long.toString(handle.pid()), "조회 중 프로세스 종료 또는 접근 제한"));
            }
        }
        for (var entry : byPid.entrySet()) {
            if (result.stream().noneMatch(process -> process.pid() == entry.getKey())) {
                issues.add(new Issue("docker", entry.getValue().id(), "컨테이너 PID " + entry.getKey() + "가 수집기에서 보이지 않음"));
            }
        }
        return List.copyOf(result);
    }

    static String readProc(long pid, String file) {
        try (var stream = Files.newInputStream(Path.of("/proc", Long.toString(pid), file))) {
            return new String(stream.readNBytes(128 * 1024), StandardCharsets.UTF_8);
        } catch (Exception exception) {
            return "";
        }
    }

    static Map<String, String> parseStatus(String contents) {
        Map<String, String> values = new HashMap<>();
        contents.lines().forEach(line -> {
            int separator = line.indexOf(':');
            if (separator > 0) {
                values.put(line.substring(0, separator), line.substring(separator + 1).strip());
            }
        });
        return values;
    }

    private static Long number(String text, long scale) {
        if (text == null) { return null; }
        try { return Long.parseLong(text.split("\\s+", 2)[0]) * scale; }
        catch (NumberFormatException exception) { return null; }
    }

    private static Integer integer(String text) {
        Long value = number(text, 1);
        return value == null ? null : value.intValue();
    }
}
