package com.wook.observer.model;

import java.util.List;

/** OS evidence and installed packages in the collector's filesystem namespace. */
public record SystemInventory(List<OsDetail> os, RpmInventory rpm, SocketInventory sockets, SocketInventory connections, List<OsDetail> networkSettings) {
    public SystemInventory {
        os = os == null ? List.of() : List.copyOf(os);
        rpm = rpm == null ? new RpmInventory(Status.NOT_COLLECTED, "이 스냅샷에서는 RPM을 수집하지 않았습니다.", List.of()) : rpm;
        sockets = sockets == null ? new SocketInventory(Status.NOT_COLLECTED, "이 스냅샷에서는 열린 포트를 수집하지 않았습니다.", List.of()) : sockets;
        connections = connections == null ? new SocketInventory(Status.NOT_COLLECTED, "이 스냅샷에서는 TCP 연결을 수집하지 않았습니다. 새 SH로 다시 수집하세요.", List.of()) : connections;
        networkSettings = networkSettings == null ? List.of() : List.copyOf(networkSettings);
    }
    // compatibility constructors keep old JSON snapshots explicitly uncollected.
    public SystemInventory(List<OsDetail> os, RpmInventory rpm, SocketInventory sockets) { this(os, rpm, sockets, null, null); }
    public SystemInventory(List<OsDetail> os, RpmInventory rpm) { this(os, rpm, null); }

    public static SystemInventory notCollected() { return new SystemInventory(List.of(), null); }

    public enum Status {
        COLLECTED("수집 완료"), UNAVAILABLE("파일·도구 없음"), FAILED("조회 실패"),
        TIMEOUT("시간 제한"), TRUNCATED("일부 수집·제한"), NOT_COLLECTED("미수집");
        private final String label;
        Status(String label) { this.label = label; }
        public String label() { return label; }
    }

    public record OsDetail(String key, String source, Status status, String value) { }
    public record SocketInventory(Status status, String message, List<Socket> entries) {
        public SocketInventory { entries = entries == null ? List.of() : List.copyOf(entries); }
    }
    public record Socket(String protocol, String state, String localAddress, int localPort, List<SocketProcess> processes,
                         String peerAddress, Integer peerPort, String source) {
        public Socket {
            processes = processes == null ? List.of() : List.copyOf(processes);
            peerAddress = peerAddress == null ? "" : peerAddress;
            source = source == null ? "이전 수집" : source;
        }
        public Socket(String protocol, String state, String localAddress, int localPort, List<SocketProcess> processes) {
            this(protocol, state, localAddress, localPort, processes, "", null, "이전 수집");
        }
    }
    public record SocketProcess(long pid, String name) { }
    public record RpmInventory(Status status, String message, List<RpmPackage> packages,
                               List<String> enabledRepositories, String releaseVersion, Status repositoryStatus) {
        public RpmInventory {
            packages = packages == null ? List.of() : List.copyOf(packages);
            enabledRepositories = enabledRepositories == null ? List.of() : List.copyOf(enabledRepositories);
            releaseVersion = releaseVersion == null ? "" : releaseVersion;
            repositoryStatus = repositoryStatus == null ? Status.NOT_COLLECTED : repositoryStatus;
        }
        public RpmInventory(Status status, String message, List<RpmPackage> packages) {
            this(status, message, packages, List.of(), "", Status.NOT_COLLECTED);
        }
    }
    public record RpmPackage(String name, String epoch, String version, String release, String architecture,
                             String sourceRpm, String modularityLabel, String vendor) {
        public RpmPackage {
            sourceRpm = sourceRpm == null ? "" : sourceRpm;
            modularityLabel = modularityLabel == null ? "" : modularityLabel;
            vendor = vendor == null ? "" : vendor;
        }
        public RpmPackage(String name, String epoch, String version, String release, String architecture) {
            this(name, epoch, version, release, architecture, "", "", "");
        }
        public String evr() { return epoch + ":" + version + "-" + release; }
        public String nevra() { return name + "-" + epoch + ":" + version + "-" + release + "." + architecture; }
    }
}
