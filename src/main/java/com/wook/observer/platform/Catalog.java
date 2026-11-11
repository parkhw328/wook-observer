package com.wook.observer.platform;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.zip.ZipInputStream;

/** Atomic upserts of OSV records and explicitly mapped CCE/configuration checks. */
@Service
@Profile("!collector")
public class Catalog {
    public static final int MAX_UPLOAD = 64 * 1024 * 1024;
    private static final long MAX_EXPANDED = 256L * 1024 * 1024;
    public static final List<String> CHECKS = List.of("ssh.permitrootlogin", "ssh.passwordauthentication",
            "kernel.randomize_va_space", "kernel.kptr_restrict", "docker.privileged", "docker.readOnlyRootfs", "docker.user");
    private final Store store;
    private final TransactionTemplate transactions;
    private final JsonMapper json = JsonMapper.builder().build();
    public Catalog(Store store, TransactionTemplate transactions) { this.store = store; this.transactions = transactions; }

    public Map<String, Object> status() {
        var state = new LinkedHashMap<>(store.one("SELECT * FROM catalog_state WHERE singleton = 1"));
        state.put("counts", store.rows("SELECT kind, COUNT(*) AS count FROM catalog_entry GROUP BY kind ORDER BY kind"));
        state.put("supportedChecks", CHECKS);
        return state;
    }

    public synchronized Snapshot snapshot() {
        return new Snapshot(((Number) status().get("revision")).intValue(), Store.string(status(), "imported_at"),
                store.rows("SELECT document FROM catalog_entry").stream().map(row -> json.readTree(Store.string(row, "document"))).toList());
    }

    public List<Map<String, Object>> search(String query, int page) {
        int offset = Math.max(0, Math.min(page, 10000)) * 50;
        String pattern = "%" + query.toLowerCase(java.util.Locale.ROOT).replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
        return store.rows("SELECT advisory_id, kind, package_name, document FROM catalog_entry WHERE LOWER(advisory_id) LIKE ? ESCAPE '\\' OR LOWER(package_name) LIKE ? ESCAPE '\\' OR LOWER(document) LIKE ? ESCAPE '\\' ORDER BY advisory_id, kind LIMIT 50 OFFSET ?",
                pattern, pattern, pattern, offset).stream().map(row -> {
            var result = new LinkedHashMap<>(row);
            result.put("document", json.readTree(Store.string(row, "document")));
            return (Map<String, Object>) result;
        }).toList();
    }

    /** Kept separate from the legacy list endpoint to preserve existing clients. */
    public synchronized Page page(String query, int requestedPage) {
        if (query.length() > 200 || requestedPage < 0 || requestedPage > 10000) {
            throw new ApiProblem(400, "검색어 200자, 페이지 0~10000 범위로 요청하세요.");
        }
        String pattern = "%" + query.toLowerCase(java.util.Locale.ROOT).replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
        long total = store.jdbc.queryForObject("SELECT COUNT(*) FROM catalog_entry WHERE LOWER(advisory_id) LIKE ? ESCAPE '\\' OR LOWER(package_name) LIKE ? ESCAPE '\\' OR LOWER(document) LIKE ? ESCAPE '\\'", Long.class, pattern, pattern, pattern);
        int pages = (int) Math.max(1, (total + 49) / 50);
        int page = Math.min(requestedPage, pages - 1);
        return new Page(search(query, page), page, 50, total, pages, page > 0, page + 1 < pages);
    }

    public record Page(List<Map<String, Object>> items, int page, int size, long total, int totalPages,
                       boolean hasPrevious, boolean hasNext) { }

    public synchronized Map<String, Object> importBytes(byte[] bytes, String source) {
        String run = UUID.randomUUID().toString();
        String started = Instant.now().toString();
        source = source == null ? "upload" : source.substring(0, Math.min(500, source.length()));
        String origin = source;
        try {
            if (bytes.length == 0 || bytes.length > MAX_UPLOAD) { throw new ApiProblem(400, "카탈로그 파일은 1바이트~64MiB이어야 합니다."); }
            List<JsonNode> records = read(bytes);
            if (records.isEmpty()) { throw new ApiProblem(400, "가져올 CVE/CCE 레코드가 없습니다."); }
            // Validate the entire archive before touching the active catalog.
            records.forEach(this::validate);
            String digest = sha256(bytes);
            transactions.executeWithoutResult(status -> {
                store.jdbc.queryForObject("SELECT revision FROM catalog_state WHERE singleton = 1 FOR UPDATE", Integer.class);
                for (JsonNode record : records) {
                    String kind = kind(record), id = record.path("id").asText();
                    String key = sha256((kind + ":" + id).getBytes(StandardCharsets.UTF_8));
                    String packageName = packageNames(record);
                    // Refuse rollback of a newer advisory; withdrawn records remain stored and cease matching.
                    var previous = store.rows("SELECT document FROM catalog_entry WHERE entry_key = ?", key);
                    if (!previous.isEmpty()) {
                        var old = json.readTree(Store.string(previous.getFirst(), "document"));
                        if (Instant.parse(record.path("modified").asText()).isBefore(Instant.parse(old.path("modified").asText()))) { continue; }
                        store.jdbc.update("UPDATE catalog_entry SET package_name = ?, document = ? WHERE entry_key = ?", packageName, record.toString(), key);
                    } else {
                        store.jdbc.update("INSERT INTO catalog_entry VALUES (?, ?, ?, ?, ?)", key, id, kind, packageName, record.toString());
                    }
                }
                store.jdbc.update("UPDATE catalog_state SET imported_at = ?, source = ?, sha256 = ?, revision = revision + 1 WHERE singleton = 1", started, origin, digest);
                int revision = store.jdbc.queryForObject("SELECT revision FROM catalog_state WHERE singleton = 1", Integer.class);
                store.jdbc.update("INSERT INTO update_run(id, started_at, source, status, record_count, message, sha256, catalog_revision) VALUES (?, ?, ?, 'SUCCESS', ?, ?, ?, ?)",
                        run, started, origin, records.size(), "검증 후 반영; 이전 수정 시각 레코드는 유지", digest, revision);
            });
            return Map.of("id", run, "records", records.size(), "sha256", digest, "message", "카탈로그 반영 완료");
        } catch (Exception e) {
            String message = e instanceof ApiProblem ? e.getMessage() : "카탈로그 형식 또는 저장 오류. 기존 카탈로그를 유지했습니다.";
            recordFailure(run, started, origin, message);
            throw new ApiProblem(400, message);
        }
    }

    public void recordFailure(String source, String message) { recordFailure(UUID.randomUUID().toString(), Instant.now().toString(), source, message); }
    private void recordFailure(String id, String time, String source, String message) {
        store.jdbc.update("INSERT INTO update_run(id, started_at, source, status, record_count, message) VALUES (?, ?, ?, 'FAILED', 0, ?)", id, time, source.substring(0, Math.min(500, source.length())), message);
    }

    private List<JsonNode> read(byte[] bytes) throws IOException {
        List<JsonNode> result = new ArrayList<>();
        if (bytes.length > 2 && bytes[0] == 'P' && bytes[1] == 'K') {
            try (var zip = new ZipInputStream(new ByteArrayInputStream(bytes))) {
                long total = 0;
                int entries = 0;
                for (var entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) {
                    if (++entries > 50000) { throw new ApiProblem(400, "ZIP 항목 제한(50,000개) 초과"); }
                    if (entry.isDirectory()) {
                        if (zip.read() != -1) { throw new ApiProblem(400, "ZIP 디렉터리 항목에 데이터가 포함되어 있습니다."); }
                        continue;
                    }
                    byte[] data = zip.readNBytes(4 * 1024 * 1024 + 1);
                    total += data.length;
                    if (data.length > 4 * 1024 * 1024 || total > MAX_EXPANDED) { throw new ApiProblem(400, "ZIP 압축 해제 용량 제한 초과"); }
                    if (!entry.getName().endsWith(".json")) { throw new ApiProblem(400, "ZIP에는 JSON 레코드만 포함하세요."); }
                    append(json.readTree(data), result);
                }
            }
        } else { append(json.readTree(bytes), result); }
        return result;
    }

    private void append(JsonNode node, List<JsonNode> result) {
        if (node.isArray()) { node.forEach(result::add); }
        else if ("wook-catalog-1".equals(node.path("format").asText()) && node.path("records").isArray()) { node.path("records").forEach(result::add); }
        else { result.add(node); }
        if (result.size() > 50000) { throw new ApiProblem(400, "레코드 제한(50,000개) 초과"); }
    }

    private void validate(JsonNode record) {
        String id = record.path("id").asText();
        if (!id.matches("[A-Za-z0-9][A-Za-z0-9_.:-]{2,159}")) { throw new ApiProblem(400, "잘못된 취약점/규칙 ID"); }
        try { Instant.parse(record.path("modified").asText()); }
        catch (Exception e) { throw new ApiProblem(400, id + ": modified는 UTC ISO-8601 시각이어야 합니다."); }
        if (record.hasNonNull("withdrawn")) {
            try { Instant.parse(record.path("withdrawn").asText()); }
            catch (Exception e) { throw new ApiProblem(400, id + ": withdrawn 시각을 확인하세요."); }
        }
        if ("CVE".equals(kind(record))) {
            if (!record.path("affected").isArray() && !record.hasNonNull("withdrawn")) { throw new ApiProblem(400, id + ": OSV affected 목록이 필요합니다."); }
            for (var affected : record.path("affected")) {
                if (affected.path("package").path("name").asText().isBlank() || affected.path("package").path("ecosystem").asText().isBlank()) {
                    throw new ApiProblem(400, id + ": 패키지 이름과 생태계가 필요합니다.");
                }
            }
        } else {
            if (!List.of("CCE", "POLICY").contains(kind(record)) || !CHECKS.contains(record.path("check").asText())
                    || !record.path("expected").isTextual() || record.path("title").asText().isBlank()
                    || record.path("platform").asText().isBlank()) { throw new ApiProblem(400, id + ": 지원되는 점검 규칙·기대값·적용 플랫폼이 필요합니다."); }
            if ("CCE".equals(kind(record)) && (!id.matches("CCE-[0-9]+-[0-9]") || "any".equals(record.path("platform").asText())
                    || record.path("reference").asText().isBlank())) { throw new ApiProblem(400, id + ": CCE는 플랫폼과 공식 근거를 명시해야 합니다."); }
        }
    }

    private String packageNames(JsonNode record) {
        List<String> names = new ArrayList<>();
        record.path("affected").forEach(item -> names.add(item.path("package").path("name").asText()));
        String text = names.isEmpty() ? record.path("check").asText() : String.join(", ", names);
        return text.substring(0, Math.min(500, text.length()));
    }
    public static String kind(JsonNode node) { return node.path("kind").asText("CVE"); }
    public static String sha256(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (Exception e) { throw new IllegalStateException(e); }
    }
    public record Snapshot(int revision, String updatedAt, List<JsonNode> records) { }
}
