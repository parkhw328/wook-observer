package com.wook.observer.collector;

import com.wook.observer.model.Observation.Issue;
import com.wook.observer.model.SystemInventory;
import com.wook.observer.model.SystemInventory.*;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Parses bounded, untrusted records without executing release-file or package contents. */
public final class SystemInventoryParser {
    public static final int MAX_PACKAGES = 20_000;
    private static final Map<String, String> SOURCES = new LinkedHashMap<>();
    static {
        SOURCES.put("uname_all", "uname -a");
        SOURCES.put("kernel_release", "uname -r");
        SOURCES.put("kernel_version", "uname -v");
        SOURCES.put("architecture", "uname -m");
        SOURCES.put("os_release", "cat /etc/os-release");
        SOURCES.put("redhat_release", "cat /etc/redhat-release");
        SOURCES.put("proc_version", "cat /proc/version");
    }
    private final Map<String, OsDetail> os = new LinkedHashMap<>();
    private final List<RpmPackage> packages = new ArrayList<>();
    private Status rpmStatus = Status.NOT_COLLECTED;
    private boolean rpmSeen, invalidRpm, limited;
    private String rpmMessage = "";
    private final java.util.Set<String> repositories = new java.util.LinkedHashSet<>();
    private Status repositoryStatus = Status.NOT_COLLECTED;
    private boolean invalidRepositories;
    private String releaseVersion = "";
    private final List<SystemInventory.Socket> sockets = new ArrayList<>();
    private final Map<String, Status> socketQueries = new LinkedHashMap<>();
    private Status socketStatus = Status.NOT_COLLECTED;
    private boolean socketsSeen, invalidSockets;

    public void accept(String line) {
        String[] fields = line.split("\t", line.startsWith("SOCKET\t") ? 3 : -1);
        if (fields[0].equals("OSINFO") && fields.length >= 2 && SOURCES.containsKey(fields[1])) {
            String key = fields[1];
            try {
                if (fields.length != 4 || fields[3].length() > 22_000) { throw new IllegalArgumentException(); }
                Status status = Status.valueOf(fields[2]);
                byte[] bytes = Base64.getDecoder().decode(fields[3]);
                if (bytes.length > 16_384) { status = Status.TRUNCATED; }
                os.put(key, new OsDetail(key, SOURCES.get(key), status,
                        new String(bytes, 0, Math.min(bytes.length, 16_384), StandardCharsets.UTF_8)));
            } catch (IllegalArgumentException exception) {
                os.put(key, new OsDetail(key, SOURCES.get(key), Status.FAILED, ""));
            }
        } else if (fields[0].equals("RPM")) {
            rpmSeen = true;
            if (packages.size() >= MAX_PACKAGES) { limited = true; return; }
            if ((fields.length != 6 && fields.length != 9) || !valid(fields[1]) || !valid(fields[3]) || !valid(fields[4]) || !valid(fields[5])
                    || !fields[2].matches("[0-9]{1,20}|\\(none\\)")) { invalidRpm = true; return; }
            if (fields.length == 9 && java.util.Arrays.stream(fields, 6, 9).anyMatch(v -> !v.isEmpty() && !valid(v))) { invalidRpm = true; return; }
            packages.add(new RpmPackage(fields[1], fields[2].equals("(none)") ? "0" : fields[2], fields[3], fields[4], fields[5],
                    fields.length == 9 ? optional(fields[6]) : "", fields.length == 9 ? optional(fields[7]) : "", fields.length == 9 ? optional(fields[8]) : ""));
        } else if (fields[0].equals("RPM_REPO")) {
            if (fields.length != 2 || !fields[1].matches("[A-Za-z0-9_.:-]{1,200}") || repositories.size() >= 256) { invalidRepositories = true; }
            else { repositories.add(fields[1]); }
        } else if (fields[0].equals("RPM_REPO_STATUS")) {
            try {
                if (fields.length != 2) { throw new IllegalArgumentException(); }
                repositoryStatus = Status.valueOf(fields[1]);
                if (repositoryStatus != Status.COLLECTED) { invalidRepositories = true; }
            } catch (IllegalArgumentException exception) { invalidRepositories = true; }
        } else if (fields[0].equals("RPM_RELEASEVER")) {
            if (fields.length != 2 || !fields[1].matches("[0-9]{1,2}(\\.[0-9]{1,2})?")
                    || (!releaseVersion.isEmpty() && !releaseVersion.equals(fields[1]))) { invalidRepositories = true; }
            else { releaseVersion = fields[1]; }
        } else if (fields[0].equals("SOCKET")) {
            socketsSeen = true;
            try {
                if (fields.length != 3 || sockets.size() >= 4096 || fields[2].length() > 8192 || !List.of("tcp", "udp").contains(fields[1])) { throw new IllegalArgumentException(); }
                String text = fields[2].strip();
                if (text.matches("^" + fields[1] + "\\s+.*")) { text = text.substring(fields[1].length()).strip(); }
                String[] columns = text.split("\\s+", 6);
                if (columns.length < 5 || !columns[0].matches("[A-Z-]{1,20}") || (fields[1].equals("tcp") && !columns[0].equals("LISTEN"))) { throw new IllegalArgumentException(); }
                String endpoint = columns[3];
                int split = endpoint.lastIndexOf(':');
                if (split < 1) { throw new IllegalArgumentException(); }
                String address = endpoint.substring(0, split);
                if (address.startsWith("[") && address.endsWith("]")) { address = address.substring(1, address.length() - 1); }
                if (address.length() > 256 || address.codePoints().anyMatch(Character::isISOControl)) { throw new IllegalArgumentException(); }
                int port = Integer.parseInt(endpoint.substring(split + 1));
                if (port < 1 || port > 65535) { throw new IllegalArgumentException(); }
                List<SocketProcess> processes = new ArrayList<>();
                if (columns.length == 6) {
                    var owner = java.util.regex.Pattern.compile("\\(\"([^\"\\r\\n]{0,128})\",pid=([0-9]{1,18}),").matcher(columns[5]);
                    while (owner.find()) {
                        if (processes.size() == 64) { invalidSockets = true; break; }
                        processes.add(new SocketProcess(Long.parseLong(owner.group(2)), owner.group(1)));
                    }
                }
                sockets.add(new SystemInventory.Socket(fields[1].toUpperCase(java.util.Locale.ROOT), columns[0], address, port, processes.stream().distinct().toList()));
            } catch (IllegalArgumentException exception) { invalidSockets = true; }
        } else if (fields[0].equals("SOCKET_QUERY_STATUS")) {
            socketsSeen = true;
            if (fields.length != 3 || !List.of("tcp", "udp").contains(fields[1])) { invalidSockets = true; }
            else {
                Status state = switch (fields[2]) { case "0" -> Status.COLLECTED; case "124", "137" -> Status.TIMEOUT; default -> Status.FAILED; };
                socketQueries.put(fields[1], state);
            }
        } else if (fields[0].equals("SOCKET_STATUS")) {
            socketsSeen = true;
            try {
                socketStatus = fields.length == 2 ? Status.valueOf(fields[1]) : Status.TRUNCATED;
                if (socketStatus == Status.TRUNCATED) { invalidSockets = true; }
            }
            catch (IllegalArgumentException exception) { invalidSockets = true; }
        } else if (fields[0].equals("RPM_STATUS")) {
            rpmSeen = true;
            try {
                rpmStatus = fields.length == 2 && fields[1].equals("NO_TIMEOUT") ? Status.UNAVAILABLE : Status.valueOf(fields[1]);
                if (fields[1].equals("NO_TIMEOUT")) { rpmMessage = "timeout 도구가 없어 RPM 조회를 실행하지 않았습니다."; }
            } catch (RuntimeException exception) { invalidRpm = true; }
        }
    }

    private String optional(String value) { return "(none)".equals(value) ? "" : value; }

    private boolean valid(String value) {
        return !value.isBlank() && value.length() <= 512 && value.codePoints().noneMatch(Character::isISOControl);
    }

    public SystemInventory build(List<Issue> issues) {
        List<OsDetail> details = SOURCES.entrySet().stream().map(entry -> os.getOrDefault(entry.getKey(),
                new OsDetail(entry.getKey(), entry.getValue(), Status.NOT_COLLECTED, ""))).toList();
        if (limited || invalidRpm || (rpmSeen && rpmStatus == Status.NOT_COLLECTED)) { rpmStatus = Status.TRUNCATED; }
        if (rpmMessage.isEmpty()) {
            rpmMessage = switch (rpmStatus) {
                case COLLECTED -> "설치 RPM DB 조회 완료. 설치 여부이며 서비스 사용·CVE 판정은 별도입니다.";
                case UNAVAILABLE -> "rpm 도구가 없어 설치 패키지를 조회하지 않았습니다.";
                case FAILED -> "RPM DB 조회 실패: 읽기 권한·DB 상태·도구 호환성을 확인하세요.";
                case TIMEOUT -> "RPM 조회 20초 제한 초과. 수집된 항목만 보존합니다.";
                case TRUNCATED -> "20,000건·4 MiB 제한 또는 불완전한 응답. 전체 목록으로 해석하지 마세요.";
                case NOT_COLLECTED -> "이 스냅샷에서는 RPM을 수집하지 않았습니다.";
            };
        }
        details.stream().filter(d -> d.status() != Status.COLLECTED
                && !(d.key().equals("redhat_release") && d.status() == Status.UNAVAILABLE))
                .forEach(d -> issues.add(new Issue("os", d.source(), d.status().label())));
        if (rpmStatus != Status.COLLECTED) { issues.add(new Issue("rpm", "rpm -qa", rpmMessage)); }
        var sorted = packages.stream().sorted(Comparator.comparing(RpmPackage::name).thenComparing(RpmPackage::architecture)
                .thenComparing(RpmPackage::version).thenComparing(RpmPackage::release).thenComparing(RpmPackage::epoch)).toList();
        if (invalidRepositories || (!repositories.isEmpty() && repositoryStatus == Status.NOT_COLLECTED)) { repositoryStatus = Status.TRUNCATED; }
        if (invalidSockets) { socketStatus = Status.TRUNCATED; }
        else if (!socketQueries.isEmpty()) {
            socketStatus = socketQueries.containsValue(Status.TIMEOUT) ? Status.TIMEOUT : socketQueries.containsValue(Status.FAILED) ? Status.FAILED
                    : socketQueries.size() == 2 ? Status.COLLECTED : Status.TRUNCATED;
        } else if (socketsSeen && socketStatus == Status.NOT_COLLECTED) { socketStatus = Status.TRUNCATED; }
        String socketMessage = switch (socketStatus) {
            case COLLECTED -> "TCP 리스닝·UDP 바인딩 소켓 조회 완료. PID/프로세스는 권한에 따라 미확인일 수 있습니다.";
            case UNAVAILABLE -> "ss 또는 timeout 도구가 없어 열린 포트를 조회하지 않았습니다.";
            case FAILED -> "소켓 조회 실패: 권한·ss 도구 상태를 확인하세요. 수집된 일부 항목만 보존합니다.";
            case TIMEOUT -> "소켓 조회 프로토콜별 5초 제한 초과. 수집된 일부 항목만 보존합니다.";
            case TRUNCATED -> "4,096개·512 KiB 제한 또는 불완전한 소켓 응답. 전체 목록으로 해석하지 마세요.";
            case NOT_COLLECTED -> "이 스냅샷에서는 열린 포트를 수집하지 않았습니다.";
        };
        if (socketsSeen && socketStatus != Status.COLLECTED) { issues.add(new Issue("network", "ss", socketMessage)); }
        var orderedSockets = sockets.stream().sorted(Comparator.comparing(SystemInventory.Socket::protocol)
                .thenComparingInt(SystemInventory.Socket::localPort).thenComparing(SystemInventory.Socket::localAddress)).toList();
        return new SystemInventory(details, new RpmInventory(rpmStatus, rpmMessage, sorted,
                List.copyOf(repositories), releaseVersion, repositoryStatus), new SocketInventory(socketStatus, socketMessage, orderedSockets));
    }
}
