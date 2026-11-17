package com.wook.observer.collector;

import com.wook.observer.model.Observation.Issue;
import com.wook.observer.model.SystemInventory.*;

import java.util.*;
import java.util.regex.Pattern;

/** Bounded socket evidence. A connection never becomes a listening service binding. */
final class NetworkInventoryParser {
    private static final int LIMIT = 4096;
    private static final Pattern OWNER = Pattern.compile("\\(\"([^\"\\r\\n]{0,128})\",(?:pid=)?([0-9]{1,18}),");
    private final List<Socket> bindings = new ArrayList<>(), connections = new ArrayList<>();
    private final Map<String, Status> queries = new LinkedHashMap<>();
    private final Map<String, OsDetail> settings = new LinkedHashMap<>();
    private Status bindingStatus = Status.NOT_COLLECTED, connectionStatus = Status.NOT_COLLECTED;
    private boolean invalidBindings, invalidConnections, bindingSeen, connectionSeen;

    void accept(String line) {
        String[] fields = line.split("\t", 3);
        String type = fields[0];
        if (type.equals("SOCKET") || type.equals("CONNECTION")) {
            boolean connection = type.equals("CONNECTION");
            if (connection) connectionSeen = true; else bindingSeen = true;
            try {
                var list = connection ? connections : bindings;
                if (fields.length != 3 || list.size() >= LIMIT || fields[2].length() > 8192) throw new IllegalArgumentException();
                Socket socket = parse(fields[1], fields[2]);
                if (connection ? !socket.protocol().equals("TCP") || socket.state().equals("LISTEN")
                        : socket.protocol().equals("TCP") && !socket.state().equals("LISTEN")) throw new IllegalArgumentException();
                list.add(socket);
            } catch (IllegalArgumentException e) { if (connection) invalidConnections = true; else invalidBindings = true; }
        } else if (type.equals("SOCKET_QUERY_STATUS")) {
            bindingSeen = true;
            if (fields.length != 3 || !Set.of("tcp", "udp").contains(fields[1]) || queries.containsKey(fields[1])) invalidBindings = true;
            else queries.put(fields[1], exitStatus(fields[2]));
        } else if (type.equals("SOCKET_STATUS") || type.equals("CONNECTION_STATUS")) {
            boolean connection = type.equals("CONNECTION_STATUS");
            if (connection) connectionSeen = true; else bindingSeen = true;
            try {
                if (fields.length != 2) throw new IllegalArgumentException();
                Status status = Status.valueOf(fields[1]);
                if (connection) { connectionStatus = status; invalidConnections |= status == Status.TRUNCATED; }
                else { bindingStatus = status; invalidBindings |= status == Status.TRUNCATED; }
            } catch (IllegalArgumentException e) { if (connection) invalidConnections = true; else invalidBindings = true; }
        } else if (type.equals("NET_SETTING")) {
            String[] values = line.split("\t", -1);
            if (values.length < 2 || !Set.of("ip_local_port_range", "ip_local_reserved_ports").contains(values[1])) return;
            String key = values[1];
            Status status = Status.FAILED; String value = "";
            try {
                if (values.length != 4 || values[3].length() > 4096 || settings.containsKey(key)) throw new IllegalArgumentException();
                status = Status.valueOf(values[2]); value = values[3].strip();
                if (status == Status.COLLECTED && !validSetting(key, value)) { status = Status.FAILED; value = ""; }
            } catch (IllegalArgumentException e) { status = Status.FAILED; value = ""; }
            settings.put(key, new OsDetail(key, "/proc/sys/net/ipv4/" + key, status, value));
        }
    }

    private static Status exitStatus(String code) {
        return switch (code) { case "0" -> Status.COLLECTED; case "124", "137" -> Status.TIMEOUT; default -> Status.FAILED; };
    }
    private static Socket parse(String format, String text) {
        boolean netstat = format.endsWith("-netstat");
        String protocol = netstat ? format.substring(0, format.length() - 8) : format;
        if (!Set.of("tcp", "udp").contains(protocol)) throw new IllegalArgumentException();
        text = text.strip();
        String state, local, peer, owners;
        List<SocketProcess> processes = new ArrayList<>();
        if (netstat) {
            String[] c = text.split("\\s+", protocol.equals("tcp") ? 7 : 6);
            if (c.length < 6 || !c[0].matches(protocol + "6?")) throw new IllegalArgumentException();
            local = c[3]; peer = c[4];
            if (protocol.equals("tcp")) { state = c[5]; owners = c.length == 7 ? c[6] : ""; }
            else { // netstat omits the state column for unconnected UDP sockets.
                String[] rest = c[5].split("\\s+", 2);
                state = rest.length == 2 ? rest[0] : "UNCONN"; owners = rest.length == 2 ? rest[1] : rest[0];
            }
            var owner = Pattern.compile("^([0-9]{1,18})/([^\\r\\n]{1,128})$").matcher(owners.strip());
            if (owner.matches()) processes.add(new SocketProcess(Long.parseLong(owner.group(1)), owner.group(2)));
        } else {
            if (text.matches("^" + protocol + "\\s+.*")) text = text.substring(protocol.length()).strip();
            String[] c = text.split("\\s+", 6);
            if (c.length < 5) throw new IllegalArgumentException();
            state = c[0]; local = c[3]; peer = c[4];
            if (c.length == 6) {
                var owner = OWNER.matcher(c[5]);
                while (owner.find()) {
                    if (processes.size() >= 64) throw new IllegalArgumentException();
                    processes.add(new SocketProcess(Long.parseLong(owner.group(2)), owner.group(1)));
                }
            }
        }
        state = state.replace('-', '_');
        state = switch (state) { case "ESTAB" -> "ESTABLISHED"; case "FIN_WAIT_1" -> "FIN_WAIT1"; case "FIN_WAIT_2" -> "FIN_WAIT2"; case "CLOSE" -> "CLOSED"; default -> state; };
        if (!state.matches("[A-Z_0-9]{1,24}")) throw new IllegalArgumentException();
        var from = endpoint(local, false); var to = endpoint(peer, true);
        return new Socket(protocol.toUpperCase(Locale.ROOT), state, from.address, from.port, processes.stream().distinct().toList(), to.address, to.port, netstat ? "netstat" : "ss");
    }
    private record Endpoint(String address, Integer port) { }
    private static Endpoint endpoint(String value, boolean peer) {
        int split = value.lastIndexOf(':');
        if (split < 1) throw new IllegalArgumentException();
        String address = value.substring(0, split), number = value.substring(split + 1);
        if (address.startsWith("[") && address.endsWith("]")) address = address.substring(1, address.length() - 1);
        if (address.isEmpty() || address.length() > 256 || address.codePoints().anyMatch(Character::isISOControl)) throw new IllegalArgumentException();
        if (peer && (number.equals("*") || number.equals("0"))) return new Endpoint(address, null);
        if (!number.matches("[0-9]{1,5}")) throw new IllegalArgumentException();
        int port = Integer.parseInt(number);
        if (port < 1 || port > 65535) throw new IllegalArgumentException();
        return new Endpoint(address, port);
    }
    private static boolean validSetting(String key, String value) {
        try {
            if (key.equals("ip_local_port_range")) {
                String[] range = value.split("\\s+");
                return range.length == 2 && port(range[0]) <= port(range[1]);
            }
            if (value.isEmpty()) return true;
            for (String part : value.split(",", -1)) {
                String[] range = part.split("-", -1);
                if (range.length == 1) port(range[0]);
                else if (range.length != 2 || port(range[0]) > port(range[1])) return false;
            }
            return true;
        } catch (IllegalArgumentException e) { return false; }
    }
    private static int port(String value) {
        if (!value.matches("[0-9]{1,5}")) throw new IllegalArgumentException();
        int port = Integer.parseInt(value);
        if (port < 1 || port > 65535) throw new IllegalArgumentException();
        return port;
    }
    List<OsDetail> settings() { return List.copyOf(settings.values()); }
    SocketInventory bindings(List<Issue> issues) {
        Status status = bindingStatus;
        if (invalidBindings) status = Status.TRUNCATED;
        else if (!queries.isEmpty()) status = queries.containsValue(Status.TIMEOUT) ? Status.TIMEOUT : queries.containsValue(Status.FAILED) ? Status.FAILED : queries.size() == 2 ? Status.COLLECTED : Status.TRUNCATED;
        else if (bindingSeen && status == Status.NOT_COLLECTED) status = Status.TRUNCATED;
        return result(status, bindings, "TCP LISTEN·UDP 바인딩", issues);
    }
    SocketInventory connections(List<Issue> issues) {
        Status status = invalidConnections || connectionSeen && connectionStatus == Status.NOT_COLLECTED ? Status.TRUNCATED : connectionStatus;
        return result(status, connections, "TCP 연결 (LISTEN 제외)", issues);
    }
    private static SocketInventory result(Status status, List<Socket> entries, String scope, List<Issue> issues) {
        String message = scope + ": " + switch (status) {
            case COLLECTED -> "조회 완료. PID/프로세스는 권한·종료 상태에 따라 미확인일 수 있습니다.";
            case UNAVAILABLE -> "ss/netstat 또는 timeout 도구가 없어 조회하지 않았습니다.";
            case FAILED -> "조회 실패. 도구·권한을 확인하세요. 일부 결과만 보존합니다.";
            case TIMEOUT -> "조회별 5초 시간 제한 초과. 일부 결과만 보존합니다.";
            case TRUNCATED -> "4,096개·512 KiB 제한 또는 불완전한 응답. 전체 목록으로 해석하지 마세요.";
            case NOT_COLLECTED -> "미수집. 기존 파일은 새 SH로 다시 수집하세요.";
        };
        if (status != Status.COLLECTED && status != Status.NOT_COLLECTED) issues.add(new Issue("network", scope, message));
        var ordered = entries.stream().sorted(Comparator.comparing(Socket::protocol).thenComparingInt(Socket::localPort)
                .thenComparing(Socket::localAddress).thenComparing(Socket::state).thenComparing(Socket::peerAddress).thenComparing(s -> s.peerPort() == null ? 0 : s.peerPort())).toList();
        return new SocketInventory(status, message, ordered);
    }
}
