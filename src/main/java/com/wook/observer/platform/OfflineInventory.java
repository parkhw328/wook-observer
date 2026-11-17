package com.wook.observer.platform;

import com.wook.observer.model.Observation;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Accepts a bounded data envelope, never an executable, archive or supplied assessment. */
@Component
@Profile("!collector")
public class OfflineInventory {
    public static final int MAX_BYTES = 16 * 1024 * 1024;
    private static final Map<String, Integer> LIMITS = Map.ofEntries(
            Map.entry("HOST", 1), Map.entry("ACCESS", 1), Map.entry("SCOPE", 1), Map.entry("DONE", 1),
            Map.entry("DOCKER", 1), Map.entry("PROCESS", 4097), Map.entry("JAVA", 64), Map.entry("JPROP", 704),
            Map.entry("LIBRARY", 32768), Map.entry("LIBISSUE", 2048), Map.entry("CONTAINER", 256), Map.entry("CPIDS", 256),
            Map.entry("FACT", 6), Map.entry("ISSUE", 2048), Map.entry("OSINFO", 7), Map.entry("RPM", 20000),
            Map.entry("RPM_STATUS", 1), Map.entry("RPM_REPO", 256), Map.entry("RPM_REPO_STATUS", 128),
            Map.entry("RPM_RELEASEVER", 1), Map.entry("SOCKET", 4096), Map.entry("SOCKET_STATUS", 1), Map.entry("SOCKET_QUERY_STATUS", 2));
    private final SshCollector collector;
    public OfflineInventory(SshCollector collector) { this.collector = collector; }

    public Parsed parse(byte[] bytes) {
        if (bytes.length == 0 || bytes.length > MAX_BYTES) throw new ApiProblem(400, "수집 결과 파일은 16 MiB 이내여야 합니다.");
        try {
            String text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
            int boundary = text.indexOf("\n\n");
            if (boundary < 0 || boundary > 512) throw invalid("파일 헤더가 없거나 손상되었습니다.");
            String[] header = text.substring(0, boundary).split("\n", -1);
            if (header.length != 4 || !header[0].equals("OBSERVER_OFFLINE\t1") || !header[1].matches("VERSION\t[0-9]{1,3}\\.[0-9]{1,3}\\.[0-9]{1,3}")
                    || !header[2].startsWith("COLLECTED_AT\t") || !header[3].matches("SHA256\t[0-9a-f]{64}")) throw invalid("파일 형식 버전·수집 시각·해시 헤더를 확인할 수 없습니다.");
            Instant collected = Instant.parse(header[2].substring(13));
            if (collected.isBefore(Instant.parse("2000-01-01T00:00:00Z")) || collected.isAfter(Instant.now().plusSeconds(600))) {
                throw new ApiProblem(400, "수집 시각을 확인하세요. 대상 서버 시계가 현재보다 10분 이상 미래이면 반입할 수 없습니다.");
            }
            String payload = text.substring(boundary + 2);
            if (!sha256(payload.getBytes(StandardCharsets.UTF_8)).equals(header[3].substring(7)))
                throw new ApiProblem(400, "파일의 무결성 검사에 실패했습니다. 편집하지 않은 원본 수집 결과를 업로드하세요.");
            if (!payload.startsWith("HOST\t") || !payload.endsWith("\nDONE\n")
                    || payload.codePoints().anyMatch(c -> c < 32 && c != '\t' && c != '\n' || c == 127)) throw invalid();
            Map<String, Integer> counts = new HashMap<>();
            Set<String> pids = new HashSet<>();
            var lines = payload.lines().iterator();
            int lineCount = 0;
            while (lines.hasNext()) {
                String line = lines.next();
                if (++lineCount > 70000) throw invalid();
                if (line.isEmpty()) continue; // Bounded shell queries may emit an empty separator.
                if (line.length() > 131072) throw invalid();
                String type = line.split("\t", 2)[0];
                if (!LIMITS.containsKey(type)) throw invalid("알 수 없는 레코드가 있습니다 (본문 " + lineCount + "행).");
                if (counts.merge(type, 1, Integer::sum) > LIMITS.get(type)) throw invalid(type + " 레코드가 허용 개수를 초과했습니다.");
                if (type.equals("SCOPE") && !line.matches("SCOPE\t(host|container)")) throw invalid();
                if (type.equals("HOST")) {
                    String[] fields = line.split("\t", -1);
                    if (fields.length != 6 || fields[1].length() > 253
                            || fields[2].length() > 256 || fields[3].length() > 64 || !fields[4].matches("[1-9][0-9]{0,6}")) throw invalid("HOST 기본 정보가 불완전합니다. 최신 SH로 다시 수집하세요.");
                    double memory = Double.parseDouble(fields[5]);
                    if (!Double.isFinite(memory) || memory < 0 || memory >= Long.MAX_VALUE) throw invalid();
                }
                if (type.equals("CPIDS")) {
                    String[] fields = line.split("\t", -1);
                    if (fields.length != 3) throw invalid();
                    String[] ids = fields[2].split(",", -1);
                    if (ids.length - (ids[ids.length - 1].isEmpty() ? 1 : 0) > 4096) throw invalid();
                    for (int i = 0; i < ids.length; i++) {
                        if (ids[i].isEmpty() && i == ids.length - 1) continue;
                        if (!ids[i].matches("[0-9]{1,10}")) throw invalid();
                    }
                }
                if (type.equals("PROCESS")) {
                    String[] fields = line.substring(8).strip().split("\\s+", 6);
                    if (fields.length != 6 || !fields[0].matches("[1-9][0-9]{0,9}") || !fields[1].matches("[0-9]{1,10}")
                            || !fields[3].matches("[0-9]{1,15}") || !fields[4].matches("[0-9]{1,8}") || !pids.add(fields[0])) throw invalid("프로세스 정보가 불완전하거나 중복되었습니다 (본문 " + lineCount + "행). 구형 ps 호환성을 위해 최신 SH로 다시 수집하세요.");
                }
            }
            if (!List.of("HOST", "ACCESS", "SCOPE", "DONE").stream().allMatch(key -> counts.getOrDefault(key, 0) == 1)) throw invalid();
            var report = collector.parse(payload);
            if (report == null || report.host().name().isBlank() || report.host().name().length() > 253 || report.host().processors() < 1
                    || report.host().physicalMemoryBytes() < 0 || !report.facts().containsKey("host:effective_uid")
                    || report.issues().stream().anyMatch(i -> i.message().equals("Malformed inventory record skipped"))) throw invalid();
            var facts = new LinkedHashMap<>(report.facts());
            String hash = sha256(bytes);
            facts.put("collection:method", "UPLOAD");
            facts.put("collection:sha256", hash);
            facts.put("collection:collector_version", header[1].substring(8));
            var host = report.host();
            var issues = new java.util.ArrayList<>(report.issues().stream().map(i -> new Observation.Issue(
                    i.layer(), i.target().replace("SSH target", "업로드 서버"), i.message().replace("SSH target", "업로드 서버"))).toList());
            issues.add(new Observation.Issue("scope", "업로드 파일", "서버에서 실행한 스크립트 결과를 반입함. SHA-256은 파일 손상 확인용이며 서버 신원을 인증하지 않음. 판정은 반입 시점의 내부 카탈로그 기준."));
            return new Parsed(new Observation(report.version(), collected, new Observation.Host(host.name(), host.os(), host.osVersion(),
                    host.architecture(), host.processors(), host.physicalMemoryBytes(), host.scope().replace("SSH ", "파일 업로드 · "), host.dockerVersion()),
                    report.processes(), report.javaProcesses(), report.containers(), issues, facts, null, report.systemInventory()), hash);
        } catch (ApiProblem problem) {
            if (problem.status == 502) throw invalid(problem.getMessage());
            throw problem;
        } catch (java.nio.charset.CharacterCodingException exception) {
            throw invalid("파일이 UTF-8 형식이 아닙니다. 내용을 변환하지 않은 원본 파일을 선택하세요.");
        } catch (Exception exception) { throw invalid(); }
    }

    public static String sha256(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
    private static ApiProblem invalid() { return new ApiProblem(400, "지원하는 완전한 Observer 수집 결과가 아닙니다. 수집용 .sh가 만든 원본 .observer 파일을 선택하세요."); }
    private static ApiProblem invalid(String reason) { return new ApiProblem(400, "수집 결과를 읽을 수 없습니다. " + reason); }
    public record Parsed(Observation report, String sha256) { }
}
