package com.wook.observer.model;

import com.wook.observer.model.SystemInventory.*;

import java.util.*;
import java.util.stream.Stream;

/** Display evidence for one saved observation; never used to create service identity keys. */
public record NetworkObservation(String collectedAt, Coverage bindings, Coverage connections, List<OsDetail> settings, List<Entry> entries) {
    public record Coverage(Status status, String message, int count) { }
    public record Owner(long pid, String name, String executable, String containerId) { }
    public record Entry(Socket socket, List<Owner> owners, boolean systemService, Boolean reservedPort, Boolean ephemeralRange) {
        public String classification() {
            var values = new ArrayList<String>();
            if (systemService) values.add("시스템 서비스 후보");
            if (Boolean.TRUE.equals(reservedPort)) values.add("로컬 예약 포트");
            if (Boolean.TRUE.equals(ephemeralRange)) values.add("자동 할당 범위 내");
            if (reservedPort == null || ephemeralRange == null) values.add("포트 정책 일부 미확인");
            return values.isEmpty() ? "일반 / 미분류" : String.join(" · ", values);
        }
    }
    private static final Set<String> SYSTEM = Set.of(
            "init", "systemd", "systemd-resolved", "systemd-networkd", "systemd-timesyncd", "systemd-journald", "systemd-udevd", "systemd-logind",
            "systemd-resolve", "systemd-network", "systemd-timesyn", "systemd-journal", "sshd", "chronyd", "ntpd", "rpcbind", "rpc.statd", "rpc.mountd", "rpc.idmapd", "rpc.gssd", "rpc.nfsd",
            "avahi-daemon", "dhclient", "dhcpcd", "networkmanager", "cupsd", "cups-browsed");
    public static NetworkObservation from(Observation report) {
        var inventory = report == null ? SystemInventory.notCollected() : report.systemInventory();
        Map<Long, Observation.ProcessInfo> processes = new HashMap<>();
        Set<Long> containerPids = new HashSet<>();
        if (report != null) {
            if (report.processes() != null) report.processes().forEach(p -> processes.put(p.pid(), p));
            if (report.containers() != null) report.containers().forEach(c -> { containerPids.add(c.initPid()); if (c.processIds() != null) containerPids.addAll(c.processIds()); });
        }
        var rows = Stream.concat(inventory.sockets().entries().stream(), inventory.connections().entries().stream()).map(socket -> {
            var owners = socket.processes().stream().map(owner -> {
                var process = processes.get(owner.pid());
                String container = process == null || process.containerId() == null ? "" : process.containerId();
                if (container.isEmpty() && containerPids.contains(owner.pid())) container = "관측 컨테이너";
                return new Owner(owner.pid(), process == null ? owner.name() : process.name(), process == null ? "" : process.executable(), container);
            }).toList();
            return new Entry(socket, owners, !owners.isEmpty() && owners.stream().allMatch(NetworkObservation::systemOwner),
                    contains(inventory.networkSettings(), "ip_local_reserved_ports", socket.localPort()),
                    contains(inventory.networkSettings(), "ip_local_port_range", socket.localPort()));
        }).toList();
        return new NetworkObservation(report == null ? "" : report.collectedAt().toString(),
                coverage(inventory.sockets()), coverage(inventory.connections()), inventory.networkSettings(), rows);
    }
    private static Coverage coverage(SocketInventory inventory) { return new Coverage(inventory.status(), inventory.message(), inventory.entries().size()); }
    private static boolean systemOwner(Owner owner) {
        if (owner.containerId() != null && !owner.containerId().isEmpty()) return false;
        String executable = owner.executable() == null ? "" : owner.executable().strip().replaceFirst(" \\(deleted\\)$", "");
        String name = owner.name() == null ? "" : owner.name().strip().toLowerCase(Locale.ROOT);
        if (executable.isEmpty()) return SYSTEM.contains(name) || name.matches("\\[?(?:kernel|kthreadd|kworker(?:[/:][^\\]]+)?|ksoftirqd/\\d+|migration/\\d+|watchdog/\\d+|rcu_(?:sched|bh|preempt)|kswapd\\d*|kcompactd\\d*|khugepaged|kthrotld|nfsd|lockd|rpciod|xprtiod)\\]?");
        return executable.matches("(?:/(?:usr/)?(?:s?bin)/|/(?:usr/)?lib(?:64)?/systemd/)[^/]+")
                && SYSTEM.contains(executable.substring(executable.lastIndexOf('/') + 1).toLowerCase(Locale.ROOT));
    }
    private static Boolean contains(List<OsDetail> settings, String key, int port) {
        var setting = settings.stream().filter(s -> s.key().equals(key) && s.status() == Status.COLLECTED).findFirst();
        if (setting.isEmpty()) return null;
        String value = setting.get().value().strip();
        try {
            if (key.equals("ip_local_port_range")) {
                String[] range = value.split("\\s+");
                return range.length == 2 ? port >= Integer.parseInt(range[0]) && port <= Integer.parseInt(range[1]) : null;
            }
            if (value.isEmpty()) return false;
            for (String part : value.split(",")) {
                String[] range = part.split("-"); int first = Integer.parseInt(range[0]), last = range.length == 1 ? first : Integer.parseInt(range[1]);
                if (port >= first && port <= last) return true;
            }
            return false;
        } catch (IllegalArgumentException e) { return null; }
    }
    public static String endpoint(String address, Integer port) {
        if (address == null || address.isEmpty()) return "미수집";
        return (address.contains(":") ? "[" + address + "]" : address) + ":" + (port == null ? "*" : port);
    }
    public static String owners(Entry entry) {
        return entry.owners().isEmpty() ? "미확인 (권한·종료 등)" : String.join(" / ", entry.owners().stream().map(p -> "PID " + p.pid() + " " + p.name()).toList());
    }
}
