package com.wook.observer.platform;

import com.wook.observer.model.Assessment;
import com.wook.observer.model.Observation;
import org.apache.maven.artifact.versioning.ComparableVersion;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

@Component
@Profile("!collector")
public class Assessor {
    private static final Pattern COORDINATE = Pattern.compile("[A-Za-z0-9_.-]+:[A-Za-z0-9_.-]+");
    private static final Pattern FILENAME = Pattern.compile("(.+?)-(\\d[^/]*)\\.jar");
    private final Catalog catalog;
    public Assessor(Catalog catalog) { this.catalog = catalog; }
    public Observation assess(Observation report) { return assess(report, catalog.snapshot()); }

    public Observation assess(Observation report, Catalog.Snapshot snapshot) {
        List<Assessment.Finding> findings = new ArrayList<>();
        List<Assessment.Coverage> coverage = new ArrayList<>();
        Map<String, List<Advisory>> byPackage = new HashMap<>();
        Map<String, Set<String>> byArtifact = new HashMap<>();
        for (JsonNode record : snapshot.records()) {
            if (record.hasNonNull("withdrawn")) { continue; }
            if ("CVE".equals(Catalog.kind(record))) {
                Map<String, List<JsonNode>> packages = new HashMap<>();
                for (JsonNode affected : record.path("affected")) {
                    String name = affected.path("package").path("name").asText();
                    if ("Maven".equals(affected.path("package").path("ecosystem").asText()) && COORDINATE.matcher(name).matches()) {
                        packages.computeIfAbsent(name, k -> new ArrayList<>()).add(affected);
                        byArtifact.computeIfAbsent(name.substring(name.indexOf(':') + 1), k -> new LinkedHashSet<>()).add(name);
                    }
                }
                packages.forEach((name, affected) -> byPackage.computeIfAbsent(name, k -> new ArrayList<>()).add(new Advisory(record, affected)));
            } else { configuration(report, record, findings, coverage); }
        }
        for (var java : report.javaProcesses()) {
            for (var library : java.libraries().stream().distinct().toList()) {
                String asset = Assessment.libraryAsset(java.pid(), library);
                Identity identity = identify(library, byArtifact);
                var advisories = byPackage.getOrDefault(identity.name(), List.of());
                if (!identity.reason().isEmpty() || advisories.isEmpty()) {
                    coverage.add(new Assessment.Coverage("CVE", asset, "미판정", !identity.reason().isEmpty() ? identity.reason() : "패키지 기준 없음", identity.name(), library.version()));
                    continue;
                }
                boolean found = false, unknown = false;
                for (Advisory advisory : advisories) {
                    var results = advisory.affected().stream().map(a -> evaluate(library.version(), a)).toList();
                    Match match = results.stream().anyMatch(r -> r.match() == Match.YES) ? Match.YES
                            : results.stream().anyMatch(r -> r.match() == Match.UNKNOWN) ? Match.UNKNOWN : Match.NO;
                    if (match == Match.UNKNOWN) { unknown = true; }
                    if (match != Match.YES) { continue; }
                    found = true;
                    var record = advisory.record();
                    List<String> aliases = new ArrayList<>();
                    aliases.add(record.path("id").asText());
                    record.path("aliases").forEach(a -> aliases.add(a.asText()));
                    var identifiers = aliases.stream().distinct().sorted().toList();
                    String id = identifiers.stream().filter(a -> a.startsWith("CVE-")).findFirst().orElse(record.path("id").asText());
                    var matched = results.stream().filter(r -> r.match() == Match.YES).toList();
                    String fixed = String.join(", ", matched.stream().flatMap(r -> r.fixed().stream()).distinct().sorted().toList());
                    String evidence = identity.name() + "@" + library.version() + "; "
                            + String.join("; ", matched.stream().flatMap(r -> r.evidence().stream()).distinct().toList());
                    if (!identity.confirmed()) { evidence += "; 파일명/메타데이터 후보: Maven 좌표 확인 필요"; }
                    String recommendation = (identity.confirmed() ? "" : "Maven 좌표 확인 후 ")
                            + (fixed.isEmpty() ? "공식 권고에서 수정 버전/완화책 확인" : "해당 영향 구간 수정 경계: " + fixed + "; 호환 분기와 다른 권고도 검토");
                    findings.add(new Assessment.Finding("CVE", id, asset, identity.confirmed() ? "발견" : "의심",
                            record.path("database_specific").path("severity").asText("UNKNOWN"),
                            record.path("summary").asText(id), evidence, reference(record), identity.name(), library.version(), recommendation, identifiers));
                }
                String state = found ? (identity.confirmed() ? "발견" : "의심") : unknown || !identity.confirmed() ? "미판정" : "미발견";
                coverage.add(new Assessment.Coverage("CVE", asset, state, found ? "영향 버전 일치" : unknown ? "버전 범위 미지원/불완전"
                        : !identity.confirmed() ? "파일명 후보만 식별" : "등록된 영향 범위 밖", identity.name(), library.version()));
                if (found && unknown) { coverage.add(new Assessment.Coverage("CVE", asset, "미판정", "일부 권고 범위 미지원/불완전", identity.name(), library.version())); }
            }
        }
        String scope = "Maven 버전 및 적용 OS의 설정 규칙 대조. 발견·의심만 조치 목록에 포함하며 비대상·미판정은 제외합니다. 미판정은 안전을 뜻하지 않습니다. 실제 로딩·악용 가능성·RPM/비 Java CVE·SSH Match는 별도 검토입니다.";
        return report.assessed(new Assessment(snapshot.revision(), snapshot.updatedAt(), Instant.now(), scope,
                findings.stream().distinct().toList(), coverage.stream().distinct().toList()));
    }

    private Identity identify(Observation.Library library, Map<String, Set<String>> byArtifact) {
        String version = library.version();
        if (version == null || !version.matches("[0-9][A-Za-z0-9._+\\-]*")) { return new Identity(library.name(), false, "버전 미확인"); }
        if (COORDINATE.matcher(library.name()).matches()) {
            boolean verified = library.evidence().toLowerCase(Locale.ROOT).contains("maven");
            return new Identity(library.name(), verified, "");
        }
        String filename = library.path().substring(library.path().lastIndexOf('/') + 1);
        var matcher = FILENAME.matcher(filename);
        if (!matcher.matches()) { return new Identity(library.name(), false, "Maven 좌표 미확인"); }
        if (compare(version, matcher.group(2)) != 0) { return new Identity(library.name(), false, "파일명/메타데이터 버전 불일치"); }
        Set<String> names = byArtifact.getOrDefault(matcher.group(1), Set.of());
        return names.size() == 1 ? new Identity(names.iterator().next(), false, "")
                : new Identity(library.name(), false, names.size() > 1 ? "Maven 후보 다수" : "Maven 좌표/파일명 후보 없음");
    }

    private void configuration(Observation report, JsonNode rule, List<Assessment.Finding> findings, List<Assessment.Coverage> coverage) {
        String check = rule.path("check").asText(), kind = Catalog.kind(rule);
        var facts = report.facts() == null ? Map.<String, String>of() : report.facts();
        String platform = rule.path("platform").asText();
        String os = facts.getOrDefault("host:os.id", ""), version = facts.getOrDefault("host:os.version", "");
        boolean known = !os.isBlank() && !version.isBlank();
        boolean applicable = "any".equals(platform) || platform.equals(os + ":" + version.split("\\.")[0]);
        List<String> assets = check.startsWith("docker.") ? report.containers().stream().map(c -> "container:" + c.id()).toList() : List.of("host");
        for (String asset : assets) {
            String actual = facts.get(asset + ":" + check);
            String state = !applicable ? known ? "비대상" : "미판정" : actual == null ? "미판정"
                    : rule.path("expected").asText().equals(actual) ? "미발견" : "발견";
            coverage.add(new Assessment.Coverage(kind, asset, state, !applicable ? known ? "적용 OS 불일치" : "OS 정보 미수집"
                    : actual == null ? "설정 값 미수집" : "설정 대조 완료", rule.path("id").asText(), ""));
            if (!"발견".equals(state)) { continue; }
            findings.add(new Assessment.Finding(kind, rule.path("id").asText(), asset, state, rule.path("severity").asText("UNKNOWN"),
                    rule.path("title").asText(), check + "=" + actual + "; 기대값=" + rule.path("expected").asText(),
                    rule.path("reference").asText(), "", "", check + " 기대값 " + rule.path("expected").asText() + " 적용 검토", List.of()));
        }
    }

    public static Match matches(String version, JsonNode affected) { return evaluate(version, affected).match(); }

    private static Evaluation evaluate(String version, JsonNode affected) {
        if (version == null || version.isBlank()) { return new Evaluation(Match.UNKNOWN, List.of(), List.of()); }
        boolean exact = false, understood = false, unsupported = false;
        for (JsonNode item : affected.path("versions")) { if (version.equals(item.asText())) { exact = true; } }
        List<String> evidence = new ArrayList<>(), fixed = new ArrayList<>();
        if (exact) { evidence.add("명시적 영향 버전 " + version); }
        for (JsonNode range : affected.path("ranges")) {
            if (!"ECOSYSTEM".equals(range.path("type").asText()) || !range.path("events").isArray() || range.path("events").isEmpty()) { unsupported = true; continue; }
            List<Interval> intervals = new ArrayList<>();
            String start = null;
            boolean valid = true;
            for (JsonNode event : range.path("events")) {
                if (event.size() != 1) { valid = false; break; }
                if (event.has("introduced") && start == null && event.path("introduced").isTextual() && !event.path("introduced").asText().isBlank()) {
                    start = event.path("introduced").asText();
                } else if (start != null && (event.has("fixed") || event.has("last_affected") || event.has("limit"))) {
                    String key = event.has("fixed") ? "fixed" : event.has("last_affected") ? "last_affected" : "limit";
                    String end = event.path(key).asText();
                    if (!event.path(key).isTextual() || end.isBlank() || (!"0".equals(start) && compare(start, end) > 0)) { valid = false; break; }
                    intervals.add(new Interval(start, end, key));
                    start = null;
                } else { valid = false; break; }
            }
            if (!valid) { unsupported = true; continue; }
            if (start != null) { intervals.add(new Interval(start, "", "")); }
            understood = true;
            for (var interval : intervals) {
                if (!("0".equals(interval.start()) || compare(version, interval.start()) >= 0)) { continue; }
                if (!interval.end().isEmpty() && !(compare(version, interval.end()) < 0 || "last_affected".equals(interval.key()) && compare(version, interval.end()) == 0)) { continue; }
                evidence.add(("0".equals(interval.start()) ? "모든 이전 버전" : ">= " + interval.start()) + (interval.end().isEmpty() ? " (상한 없음)"
                        : ("last_affected".equals(interval.key()) ? " ~ <= " : " ~ < ") + interval.end()));
                if ("fixed".equals(interval.key())) { fixed.add(interval.end()); }
            }
        }
        return new Evaluation(!evidence.isEmpty() ? Match.YES : understood && !unsupported ? Match.NO : Match.UNKNOWN, fixed, evidence);
    }

    private static int compare(String a, String b) { return new ComparableVersion(a).compareTo(new ComparableVersion(b)); }
    private String reference(JsonNode record) {
        for (JsonNode link : record.path("references")) { if ("ADVISORY".equals(link.path("type").asText())) { return link.path("url").asText(); } }
        return "https://osv.dev/vulnerability/" + record.path("id").asText();
    }
    public enum Match { YES, NO, UNKNOWN }
    private record Advisory(JsonNode record, List<JsonNode> affected) { }
    private record Identity(String name, boolean confirmed, String reason) { }
    private record Interval(String start, String end, String key) { }
    private record Evaluation(Match match, List<String> fixed, List<String> evidence) { }
}
