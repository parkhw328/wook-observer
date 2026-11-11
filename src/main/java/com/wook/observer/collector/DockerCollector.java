package com.wook.observer.collector;

import com.wook.observer.config.ObserverProperties;
import com.wook.observer.model.Observation.ContainerInfo;
import com.wook.observer.model.Observation.Issue;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

@Component
public class DockerCollector {
    private static final String INSPECT_FORMAT = "{\"Id\":{{json .Id}},\"Name\":{{json .Name}},"
            + "\"Image\":{{json .Image}},\"ConfigImage\":{{json .Config.Image}},"
            + "\"Status\":{{json .State.Status}},\"Pid\":{{json .State.Pid}},"
            + "\"Ports\":{{json .NetworkSettings.Ports}},\"Networks\":{{json .NetworkSettings.Networks}},"
            + "\"Mounts\":{{json .Mounts}}}";
    private final CommandRunner commands;
    private final ObserverProperties properties;
    private final ServiceClassifier classifier;
    private final JsonMapper json = JsonMapper.builder().build();

    public DockerCollector(CommandRunner commands, ObserverProperties properties, ServiceClassifier classifier) {
        this.commands = commands;
        this.properties = properties;
        this.classifier = classifier;
    }

    public Result collect(List<Issue> issues) {
        if (!properties.isDockerEnabled()) {
            issues.add(new Issue("docker", "engine", "설정에 의해 Docker 수집 비활성화"));
            return new Result("미수집", List.of());
        }
        if (!Path.of(properties.getDockerSocket()).isAbsolute()) {
            issues.add(new Issue("docker", "engine", "docker-socket은 로컬 Unix 소켓의 절대 경로여야 함"));
            return new Result("미수집", List.of());
        }
        var version = run("version", "--format", "{{.Server.Version}}");
        if (!version.success()) {
            issues.add(new Issue("docker", "engine", version.failure()));
            return new Result("미확인", List.of());
        }
        var listing = run("container", "ls", "--all", "--quiet", "--no-trunc");
        if (!listing.success()) {
            issues.add(new Issue("docker", "containers", listing.failure()));
            return new Result(version.output().strip(), List.of());
        }
        List<String> ids = listing.output().lines().filter(value -> !value.isBlank()).toList();
        if (ids.size() > properties.getMaxContainers()) {
            issues.add(new Issue("docker", "containers", "컨테이너 수 제한으로 일부 생략: " + ids.size()));
        }
        List<ContainerInfo> containers = new ArrayList<>();
        for (String id : ids.stream().limit(properties.getMaxContainers()).toList()) {
            if (!id.matches("[a-f0-9]{12,64}")) {
                issues.add(new Issue("docker", "containers", "유효하지 않은 컨테이너 식별자"));
                continue;
            }
            var inspection = run("container", "inspect", "--format", INSPECT_FORMAT, id);
            if (!inspection.success()) {
                issues.add(new Issue("docker", id, inspection.failure()));
                continue;
            }
            try {
                JsonNode node = json.readTree(inspection.output());
                List<Long> pids = new ArrayList<>();
                if ("running".equals(node.path("Status").asString())) {
                    if (insideContainer()) {
                        issues.add(new Issue("docker", id, "수집기가 컨테이너 안에서 실행 중: 호스트 PID 연결 생략"));
                    } else {
                        var top = run("container", "top", id, "-eo", "pid,comm");
                        if (top.success()) {
                            pids.addAll(parseProcessIds(top.output()));
                        } else {
                            issues.add(new Issue("docker", id, top.failure()));
                        }
                    }
                }
                containers.add(parseContainer(node, pids));
            } catch (Exception exception) {
                issues.add(new Issue("docker", id, "컨테이너 메타데이터 해석 실패"));
            }
        }
        return new Result(version.output().strip(), List.copyOf(containers));
    }

    public ContainerInfo parseContainer(JsonNode node, List<Long> pids) {
        String name = node.path("Name").asString().replaceFirst("^/", "");
        String image = node.path("ConfigImage").asString();
        var category = classifier.classify("container:" + name, image);
        List<String> ports = new ArrayList<>();
        for (var entry : node.path("Ports").properties()) {
            if (entry.getValue().isNull() || entry.getValue().isEmpty()) {
                ports.add(entry.getKey() + " (컨테이너 내부)");
            } else {
                for (JsonNode binding : entry.getValue()) {
                    ports.add(binding.path("HostIp").asString() + ":" + binding.path("HostPort").asString()
                            + " -> " + entry.getKey());
                }
            }
        }
        List<String> networks = new ArrayList<>();
        for (var entry : node.path("Networks").properties()) {
            networks.add(entry.getKey() + " (IPv4=" + entry.getValue().path("IPAddress").asString()
                    + ", IPv6=" + entry.getValue().path("GlobalIPv6Address").asString() + ")");
        }
        List<String> mounts = new ArrayList<>();
        for (JsonNode mount : node.path("Mounts")) {
            mounts.add(mount.path("Type").asString() + ": " + mount.path("Source").asString()
                    + " -> " + mount.path("Destination").asString() + " (rw=" + mount.path("RW").asBoolean() + ")");
        }
        return new ContainerInfo(node.path("Id").asString(), name, image, node.path("Image").asString(),
                node.path("Status").asString(), node.path("Pid").asLong(), List.copyOf(pids), ports,
                networks, mounts, category.category(), category.evidence());
    }

    public static List<Long> parseProcessIds(String output) {
        return output.lines().skip(1).map(String::strip).filter(line -> !line.isBlank())
                .map(line -> line.split("\\s+", 2)[0]).filter(value -> value.matches("[1-9][0-9]*"))
                .map(Long::parseLong).distinct().sorted().toList();
    }

    public static boolean insideContainer() {
        return Files.exists(Path.of("/.dockerenv")) || Files.exists(Path.of("/run/.containerenv"));
    }

    private CommandRunner.Result run(String... args) {
        List<String> command = new ArrayList<>(List.of("docker", "--host", "unix://" + properties.getDockerSocket()));
        command.addAll(List.of(args));
        return commands.run(command);
    }

    public record Result(String engineVersion, List<ContainerInfo> containers) { }
}
