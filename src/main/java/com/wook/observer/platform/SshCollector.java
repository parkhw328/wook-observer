package com.wook.observer.platform;

import com.wook.observer.collector.ServiceClassifier;
import com.wook.observer.model.Observation;
import org.springframework.context.annotation.Profile;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

@Component
@Profile("!collector")
public class SshCollector {
    private static final int MAX_OUTPUT = 16 * 1024 * 1024;
    private static final Set<String> JVM_PROPERTIES = Set.of("java.version", "java.runtime.version", "java.vendor", "java.vm.name",
            "java.vm.version", "java.home", "java.class.path", "jdk.module.path", "os.name", "os.arch", "os.version");
    private final Targets targets;
    private final ServiceClassifier classifier;
    private final JsonMapper json = JsonMapper.builder().build();
    public SshCollector(Targets targets, ServiceClassifier classifier) { this.targets = targets; this.classifier = classifier; }

    public Observation collect(Map<String, Object> target) throws Exception {
        Instant started = Instant.now();
        Path directory = Files.createTempDirectory("observer-ssh-", PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
        Process process = null;
        var readers = Executors.newVirtualThreadPerTaskExecutor();
        try {
            Path identity = directory.resolve("identity");
            Path known = directory.resolve("known_hosts");
            Files.writeString(identity, targets.decryptKey(target));
            Files.setPosixFilePermissions(identity, PosixFilePermissions.fromString("rw-------"));
            String host = Store.string(target, "host");
            String port = Store.string(target, "port");
            Files.writeString(known, ("22".equals(port) ? host : "[" + host + "]:" + port) + " " + Store.string(target, "host_key") + "\n");
            var command = List.of("ssh", "-F", "/dev/null", "-T", "-o", "BatchMode=yes", "-o", "IdentitiesOnly=yes",
                    "-o", "StrictHostKeyChecking=yes", "-o", "UpdateHostKeys=no", "-o", "PreferredAuthentications=publickey",
                    "-o", "GlobalKnownHostsFile=/dev/null", "-o", "UserKnownHostsFile=" + known,
                    "-o", "ConnectTimeout=10", "-o", "ConnectionAttempts=1", "-o", "ServerAliveInterval=10", "-o", "ServerAliveCountMax=2",
                    "-o", "ForwardAgent=no", "-o", "ClearAllForwardings=yes", "-o", "LogLevel=ERROR",
                    "-i", identity.toString(), "-p", port, Store.string(target, "ssh_user") + "@" + host, "sh", "-s");
            process = new ProcessBuilder(command).redirectError(ProcessBuilder.Redirect.DISCARD).start();
            Process child = process;
            var output = readers.submit(() -> {
                byte[] bytes = child.getInputStream().readNBytes(MAX_OUTPUT + 1);
                if (bytes.length > MAX_OUTPUT) { child.destroyForcibly(); throw new IOException("SSH output limit exceeded"); }
                return new String(bytes, StandardCharsets.UTF_8);
            });
            try (var script = new ClassPathResource("collector/ssh-inventory.sh").getInputStream(); var stdin = process.getOutputStream()) {
                script.transferTo(stdin);
            }
            if (!process.waitFor(180, TimeUnit.SECONDS)) { process.destroyForcibly(); throw new ApiProblem(502, "SSH 점검 시간 제한(180초)을 초과했습니다."); }
            if (process.exitValue() != 0) { throw new ApiProblem(502, "SSH 연결/수집 실패. 호스트 키·접근 키·계정 권한·Linux 지원 여부를 확인하세요."); }
            var report = parse(output.get(5, TimeUnit.SECONDS));
            return new Observation(report.version(), started, report.host(), report.processes(), report.javaProcesses(), report.containers(), report.issues(), report.facts(), null);
        } finally {
            if (process != null && process.isAlive()) { process.destroyForcibly(); }
            readers.shutdownNow();
            Files.deleteIfExists(directory.resolve("identity"));
            Files.deleteIfExists(directory.resolve("known_hosts"));
            Files.deleteIfExists(directory);
        }
    }

    public Observation parse(String output) {
        List<Observation.Issue> issues = new ArrayList<>();
        List<Observation.ProcessInfo> processes = new ArrayList<>();
        List<Observation.ContainerInfo> containers = new ArrayList<>();
        Map<Long, Map<String, String>> properties = new LinkedHashMap<>();
        Map<Long, List<Observation.Library>> libraries = new HashMap<>();
        Map<String, List<Long>> containerPids = new HashMap<>();
        Map<String, String> facts = new LinkedHashMap<>();
        List<JsonNode> docker = new ArrayList<>();
        String[] host = null;
        String dockerVersion = "미확인";
        boolean done = false, isolated = false;
        for (String line : output.lines().toList()) {
            String[] parts = line.split("\t", 3);
            try {
                switch (parts[0]) {
                    case "HOST" -> host = line.split("\t");
                    case "SCOPE" -> isolated = "container".equals(parts[1]);
                    case "DOCKER" -> dockerVersion = parts[1];
                    case "PROCESS" -> {
                        if (processes.size() >= 4096) { issues.add(new Observation.Issue("process", "host", "Process limit 4096")); break; }
                        String[] values = parts[1].strip().split("\\s+", 6);
                        var classification = classifier.classify("pid:" + values[0], values[5]);
                        processes.add(new Observation.ProcessInfo(Long.parseLong(values[0]), Long.parseLong(values[1]), values[5], "", values[2],
                                null, null, Long.parseLong(values[3]) * 1024, Integer.parseInt(values[4]), classification.category(), classification.evidence(), ""));
                    }
                    case "JAVA" -> { if (properties.size() < 64) { properties.putIfAbsent(Long.parseLong(parts[1]), new LinkedHashMap<>()); } }
                    case "JPROP" -> {
                        String[] value = parts[2].split("=", 2);
                        // Recheck on the receiving side; an SSH endpoint's output is untrusted.
                        var jvm = properties.get(Long.parseLong(parts[1]));
                        if (jvm != null && value.length == 2 && JVM_PROPERTIES.contains(value[0])) {
                            jvm.put(value[0], value[1]);
                        }
                    }
                    case "LIBRARY" -> {
                        var library = json.readTree(parts[1]);
                        long pid = library.path("pid").asLong();
                        if (!properties.containsKey(pid)) { break; }
                        var items = libraries.computeIfAbsent(pid, k -> new ArrayList<>());
                        if (items.size() < 512) {
                            items.add(new Observation.Library(library.path("name").asText(), library.path("version").asText(), library.path("path").asText(),
                                    library.path("evidence").asText(), "미확인", "파일 존재 확인; 실제 로딩 미확인"));
                        }
                    }
                    case "LIBISSUE" -> {
                        var value = json.readTree(parts[1]);
                        issues.add(new Observation.Issue("java", value.path("pid").asText(), value.path("message").asText()));
                    }
                    case "CONTAINER" -> { if (docker.size() < 256) { docker.add(json.readTree(parts[1])); } }
                    case "CPIDS" -> containerPids.put(parts[1], parts.length == 3 && !parts[2].isBlank()
                            ? Arrays.stream(parts[2].split(",")).filter(p -> p.matches("[0-9]+" )).map(Long::parseLong).toList() : List.of());
                    case "FACT" -> {
                        if (parts[1].matches("host:(os\\.(id|version)|kernel\\.(randomize_va_space|kptr_restrict)|ssh\\.(permitrootlogin|passwordauthentication))")) {
                            facts.put(parts[1], parts[2]);
                        }
                    }
                    case "ISSUE" -> issues.add(new Observation.Issue(parts[1], "SSH target", parts[2]));
                    case "DONE" -> done = true;
                    default -> { }
                }
            } catch (RuntimeException e) { issues.add(new Observation.Issue("ssh", "response", "Malformed inventory record skipped")); }
        }
        if (!done || host == null || host.length != 6 || processes.isEmpty()) { throw new ApiProblem(502, "대상에서 완전한 프로세스 스냅샷을 받지 못했습니다."); }
        Map<Long, String> pidOwners = new HashMap<>();
        for (JsonNode item : docker) {
            String id = item.path("id").asText();
            String name = item.path("name").asText().replaceFirst("^/", "");
            var category = classifier.classify("container:" + name, item.path("image").asText());
            List<Long> pids = isolated ? List.of() : containerPids.getOrDefault(id, List.of());
            pids.forEach(pid -> pidOwners.put(pid, id));
            List<String> ports = new ArrayList<>(), networks = new ArrayList<>(), mounts = new ArrayList<>();
            item.path("ports").properties().forEach(entry -> ports.add(entry.getKey() + " " + entry.getValue()));
            item.path("networks").properties().forEach(entry -> networks.add(entry.getKey()));
            item.path("mounts").forEach(mount -> mounts.add(mount.path("Source").asText() + " -> " + mount.path("Destination").asText() + " (rw=" + mount.path("RW").asBoolean() + ")"));
            containers.add(new Observation.ContainerInfo(id, name, item.path("image").asText(), item.path("imageId").asText(), item.path("state").asText(),
                    item.path("pid").asLong(), pids, ports, networks, mounts, category.category(), category.evidence()));
            for (String key : List.of("privileged", "readOnlyRootfs", "user")) {
                if (item.has(key) && !item.path(key).isNull()) { facts.put("container:" + id + ":docker." + key, item.path(key).asText()); }
            }
        }
        if (isolated) { issues.add(new Observation.Issue("scope", "SSH target", "Container namespace: host PID linkage skipped")); }
        List<Observation.ProcessInfo> linked = processes.stream().map(p -> new Observation.ProcessInfo(p.pid(), p.parentPid(), p.name(), p.executable(),
                p.user(), p.startedAt(), p.cpuMillis(), p.residentBytes(), p.threads(), p.category(), p.classificationEvidence(), pidOwners.getOrDefault(p.pid(), ""))).toList();
        List<Observation.JavaInfo> java = properties.entrySet().stream().map(entry -> new Observation.JavaInfo(entry.getKey(),
                entry.getValue().isEmpty() ? "JVM 속성 점검 불가" : "선별 JVM 속성 수집", entry.getValue(), libraries.getOrDefault(entry.getKey(), List.of()))).toList();
        issues.add(new Observation.Issue("scope", "SSH target", "비 Java 패키지·동적 클래스 로더·Kubernetes·프로세스 시작/CPU 시간은 미지원. SSH 설정은 전역 값만 확인."));
        return new Observation("1.0.0", Instant.now(), new Observation.Host(host[1], "Linux", host[2], host[3], Integer.parseInt(host[4]),
                (long) Double.parseDouble(host[5]), isolated ? "SSH 컨테이너 네임스페이스" : "SSH Linux 호스트 (계정 접근 범위)", dockerVersion), linked, java, containers, issues, facts, null);
    }
}
