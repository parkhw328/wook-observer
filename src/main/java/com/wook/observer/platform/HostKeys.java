package com.wook.observer.platform;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

@Service
@Profile("!collector")
public class HostKeys {
    private static final String TYPES = "ssh-ed25519|ssh-rsa|ecdsa-sha2-nistp(?:256|384|521)";
    private final Semaphore probes = new Semaphore(2);

    public List<Key> probe(Endpoint endpoint) throws Exception {
        Targets.validateEndpoint(endpoint.host(), endpoint.port());
        if (!probes.tryAcquire()) { throw new ApiProblem(429, "호스트 키 조회가 진행 중입니다. 잠시 후 다시 시도하세요."); }
        Process process = null;
        var executor = Executors.newVirtualThreadPerTaskExecutor();
        try {
            process = new ProcessBuilder("ssh-keyscan", "-T", "5", "-p", Integer.toString(endpoint.port()), "-t", "ed25519,ecdsa,rsa", endpoint.host())
                    .redirectError(ProcessBuilder.Redirect.DISCARD).start();
            Process child = process;
            var result = executor.submit(() -> child.getInputStream().readNBytes(16_385));
            if (!process.waitFor(12, TimeUnit.SECONDS)) { throw new ApiProblem(502, "서버 호스트 키 조회 시간이 초과됐습니다."); }
            byte[] bytes = result.get(2, TimeUnit.SECONDS);
            if (bytes.length > 16_384) { throw new ApiProblem(502, "호스트 키 응답 크기를 초과했습니다."); }
            var keys = new String(bytes, StandardCharsets.UTF_8).lines().filter(line -> !line.isBlank() && !line.startsWith("#"))
                    .map(HostKeys::describe).distinct().sorted(Comparator.comparing(key -> "ssh-ed25519".equals(key.type()) ? "0" : key.type())).toList();
            if (keys.isEmpty()) { throw new ApiProblem(502, "호스트 키를 읽지 못했습니다. SSH 주소·포트·방화벽을 확인하세요."); }
            return keys;
        } finally {
            if (process != null) { process.destroyForcibly(); }
            executor.shutdownNow();
            probes.release();
        }
    }

    static String normalize(String input) { return describe(input).publicKey(); }

    static Key describe(String input) {
        try {
            if (input == null || input.length() > 8192 || input.strip().contains("\n") || input.strip().contains("\r")) { throw new IllegalArgumentException(); }
            String[] parts = input.strip().split("\\s+");
            int index = parts[0].matches(TYPES) ? 0 : 1;
            String type = parts[index], encoded = parts[index + 1];
            if (!type.matches(TYPES)) { throw new IllegalArgumentException(); }
            byte[] bytes = Base64.getDecoder().decode(encoded);
            ByteBuffer wire = ByteBuffer.wrap(bytes);
            if (!new String(field(wire), StandardCharsets.US_ASCII).equals(type)) { throw new IllegalArgumentException(); }
            if (type.equals("ssh-ed25519")) {
                if (field(wire).length != 32) { throw new IllegalArgumentException(); }
            } else if (type.equals("ssh-rsa")) {
                if (field(wire).length == 0 || field(wire).length < 128) { throw new IllegalArgumentException(); }
            } else {
                String curve = new String(field(wire), StandardCharsets.US_ASCII);
                byte[] point = field(wire);
                int size = switch (curve) { case "nistp256" -> 65; case "nistp384" -> 97; case "nistp521" -> 133; default -> 0; };
                if (!type.equals("ecdsa-sha2-" + curve) || size == 0 || point.length != size || point[0] != 4) { throw new IllegalArgumentException(); }
            }
            if (wire.hasRemaining()) { throw new IllegalArgumentException(); }
            String fingerprint = "SHA256:" + Base64.getEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(bytes));
            return new Key(type, type + " " + Base64.getEncoder().encodeToString(bytes), fingerprint);
        } catch (Exception e) { throw new ApiProblem(400, "올바른 서버 호스트 공개 키를 입력하세요. .pub 또는 known_hosts의 한 줄을 사용할 수 있습니다."); }
    }

    private static byte[] field(ByteBuffer input) {
        int length = input.getInt();
        if (length < 0 || length > 4096 || length > input.remaining()) { throw new IllegalArgumentException(); }
        byte[] value = new byte[length]; input.get(value); return value;
    }

    public record Endpoint(String host, int port) { }
    public record Key(String type, String publicKey, String fingerprint) { }
}
