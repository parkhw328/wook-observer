package com.wook.observer.platform;

import com.wook.observer.model.Observation;
import com.wook.observer.model.SystemInventory;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/** Conservative service candidates, not a claim of complete logical service discovery. */
public final class ServiceDiscovery {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private ServiceDiscovery() { }

    public record Candidate(String key, String kind, String name, String category, List<String> bindings,
                            List<Long> pids, String executable, String runUser, String image,
                            String containerId, String state) { }
    public record Summary(Observation.Host host, String distribution, String socketStatus, String socketMessage,
                          int processCount, int containerCount, int issueCount) { }

    public static Summary summary(Observation report) {
        String distribution = "";
        for (var detail : report.systemInventory().os()) {
            if (!"os_release".equals(detail.key()) || detail.status() != SystemInventory.Status.COLLECTED) continue;
            for (String line : detail.value().split("\n")) if (line.startsWith("PRETTY_NAME=")) {
                distribution = line.substring("PRETTY_NAME=".length()).strip();
                if (distribution.length() >= 2 && (distribution.startsWith("\"") && distribution.endsWith("\"")
                        || distribution.startsWith("'") && distribution.endsWith("'"))) distribution = distribution.substring(1, distribution.length() - 1);
            }
        }
        // Keep the supplied OS evidence intact; do not parse a release file as shell code.
        if (distribution.isBlank()) distribution = report.host().os() + " " + report.host().osVersion();
        var sockets = report.systemInventory().sockets();
        return new Summary(report.host(), distribution, sockets.status().name(), sockets.message(),
                safe(report.processes()).size(), safe(report.containers()).size(), safe(report.issues()).size());
    }

    public static List<Candidate> discover(Observation report) {
        Map<Long, Observation.ProcessInfo> processes = new LinkedHashMap<>();
        safe(report.processes()).forEach(process -> processes.put(process.pid(), process));
        Map<Long, Observation.JavaInfo> java = new LinkedHashMap<>();
        safe(report.javaProcesses()).forEach(jvm -> java.put(jvm.pid(), jvm));
        Map<String, Builder> found = new LinkedHashMap<>();
        Map<Long, Builder> containers = new LinkedHashMap<>();
        Map<String, Builder> containerIds = new LinkedHashMap<>();
        for (var container : safe(report.containers())) {
            String key = key("container", value(container.name()).isBlank() ? container.id() : container.name());
            var item = new Builder(key, "CONTAINER", container.name(), category(container.category()));
            item.image = value(container.image()); item.containerId = value(container.id()); item.state = value(container.state());
            // Docker declarations/mappings are labelled separately from host socket observations.
            safe(container.ports()).forEach(port -> item.bindings.add("Docker " + port));
            for (long pid : safe(container.processIds())) { item.pids.add(pid); containers.put(pid, item); }
            if (container.initPid() > 0) { item.pids.add(container.initPid()); containers.put(container.initPid(), item); }
            containerIds.put(container.id(), item);
            found.put(key, item);
        }
        for (var process : processes.values()) {
            var container = containerIds.get(process.containerId());
            if (container != null) { containers.put(process.pid(), container); container.pids.add(process.pid()); }
        }

        Map<Long, TreeSet<String>> bindings = new LinkedHashMap<>();
        Map<Long, String> names = new LinkedHashMap<>();
        for (var socket : report.systemInventory().sockets().entries()) {
            String binding = binding(socket);
            if (socket.processes().isEmpty()) {
                String key = key("socket", binding);
                var item = found.computeIfAbsent(key, ignored -> new Builder(key, "SOCKET", "소유 프로세스 미확인", "미확인"));
                item.bindings.add(binding);
            }
            for (var owner : socket.processes()) {
                if (containers.containsKey(owner.pid())) {
                    containers.get(owner.pid()).bindings.add(binding);
                } else {
                    bindings.computeIfAbsent(owner.pid(), ignored -> new TreeSet<>()).add(binding);
                    names.put(owner.pid(), owner.name());
                }
            }
        }
        // JVMs without visible listening sockets are useful candidates too.
        for (long pid : java.keySet()) if (!containers.containsKey(pid)) bindings.computeIfAbsent(pid, ignored -> new TreeSet<>());
        for (var entry : bindings.entrySet()) {
            long pid = entry.getKey();
            var process = processes.get(pid);
            String executable = process == null ? "" : value(process.executable());
            String name = process == null ? names.getOrDefault(pid, "JVM") : process.name();
            String user = process == null ? "" : value(process.user());
            String classpath = java.containsKey(pid) && java.get(pid).properties() != null
                    ? java.get(pid).properties().getOrDefault("java.class.path", "") : "";
            // No PID in identity: restarts retain annotations. Endpoint/classpath changes create a new candidate.
            String key = key("process", executable, name, user, classpath, entry.getValue());
            var item = found.computeIfAbsent(key, ignored -> new Builder(key, "PROCESS", name,
                    process == null ? "미확인" : category(process.category())));
            item.executable = executable; item.runUser = user;
            item.pids.add(pid); item.bindings.addAll(entry.getValue());
        }
        return found.values().stream().map(Builder::build).sorted(Comparator.comparing(Candidate::name).thenComparing(Candidate::key)).toList();
    }

    private static String binding(SystemInventory.Socket socket) {
        String address = socket.localAddress();
        if (address.contains(":") && !address.startsWith("[")) address = "[" + address + "]";
        return socket.protocol() + " " + address + ":" + socket.localPort();
    }
    private static String key(Object... parts) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(JSON.writeValueAsString(parts).getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
    private static String category(com.wook.observer.model.ServiceCategory category) { return category == null ? "미확인" : category.label(); }
    private static String value(String value) { return value == null ? "" : value; }
    private static <T> List<T> safe(List<T> list) { return list == null ? List.of() : list; }

    private static final class Builder {
        final String key, kind, name, category;
        final TreeSet<String> bindings = new TreeSet<>();
        final TreeSet<Long> pids = new TreeSet<>();
        String executable = "", runUser = "", image = "", containerId = "", state = "";
        Builder(String key, String kind, String name, String category) {
            this.key = key; this.kind = kind; this.name = value(name); this.category = category;
        }
        Candidate build() { return new Candidate(key, kind, name, category, List.copyOf(bindings), List.copyOf(pids), executable, runUser, image, containerId, state); }
    }
}
