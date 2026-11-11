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
import java.util.List;
import java.util.Map;

@Component
@Profile("!collector")
public class Assessor {
    private final Catalog catalog;
    public Assessor(Catalog catalog) { this.catalog = catalog; }
    public Observation assess(Observation report) { return assess(report, catalog.snapshot()); }

    public Observation assess(Observation report, Catalog.Snapshot snapshot) {
        List<Assessment.Finding> findings = new ArrayList<>();
        Map<String, List<Advisory>> byPackage = new HashMap<>();
        for (JsonNode record : snapshot.records()) {
            if (record.hasNonNull("withdrawn")) { continue; }
            if ("CVE".equals(Catalog.kind(record))) {
                Map<String, List<JsonNode>> affectedPackages = new HashMap<>();
                for (JsonNode affected : record.path("affected")) {
                    if ("Maven".equals(affected.path("package").path("ecosystem").asText())) {
                        affectedPackages.computeIfAbsent(affected.path("package").path("name").asText(), k -> new ArrayList<>()).add(affected);
                    }
                }
                affectedPackages.forEach((name, affected) -> byPackage.computeIfAbsent(name, k -> new ArrayList<>()).add(new Advisory(record, affected)));
            } else { configuration(report, record, findings); }
        }
        for (var java : report.javaProcesses()) {
            for (var library : java.libraries()) {
                String asset = Assessment.libraryAsset(java.pid(), library);
                var advisories = byPackage.getOrDefault(library.name(), List.of());
                if (!library.name().contains(":") || library.version().isBlank() || advisories.isEmpty()) {
                    findings.add(new Assessment.Finding("CVE", "", asset, "점검 불가", "UNKNOWN", library.name(),
                            advisories.isEmpty() ? "로컬 카탈로그에 해당 Maven 패키지의 적용 기준 없음. 안전 판정이 아닙니다." : "정확한 Maven 좌표/버전 미확인", ""));
                    continue;
                }
                for (Advisory advisory : advisories) {
                    var matches = advisory.affected().stream().map(affected -> matches(library.version(), affected)).toList();
                    Match match = matches.contains(Match.YES) ? Match.YES : matches.contains(Match.UNKNOWN) ? Match.UNKNOWN : Match.NO;
                    String id = advisory.record().path("id").asText();
                    for (JsonNode alias : advisory.record().path("aliases")) { if (alias.asText().startsWith("CVE-")) { id = alias.asText(); break; } }
                    String state = match == Match.YES ? (library.evidence().contains("Maven") ? "발견" : "의심")
                            : match == Match.NO ? "미발견" : "점검 불가";
                    findings.add(new Assessment.Finding("CVE", id, asset, state,
                            advisory.record().path("database_specific").path("severity").asText("UNKNOWN"),
                            advisory.record().path("summary").asText(id),
                            library.name() + "@" + library.version() + "; " + advisory.record().path("id").asText()
                                    + "; " + (match == Match.UNKNOWN ? "지원하지 않는/불완전한 버전 범위" : "패키지 버전 기준 대조; 실제 악용 가능성은 별도 검토"),
                            reference(advisory.record())));
                }
            }
        }
        String scope = "Maven 좌표·버전의 OSV 대조 및 등록된 CCE/설정 규칙. OS/비 Java 패키지 CVE·악용 가능성·SSH Match 블록은 미판정. 미발견은 저장된 적용 기준 범위에 한정.";
        return report.assessed(new Assessment(snapshot.revision(), snapshot.updatedAt(), Instant.now(), scope, findings));
    }

    private void configuration(Observation report, JsonNode rule, List<Assessment.Finding> findings) {
        String check = rule.path("check").asText();
        var facts = report.facts() == null ? Map.<String, String>of() : report.facts();
        String platform = rule.path("platform").asText();
        String actualPlatform = facts.getOrDefault("host:os.id", "") + ":" + facts.getOrDefault("host:os.version", "").split("\\.")[0];
        boolean applicable = "any".equals(platform) || platform.equals(actualPlatform);
        List<String> assets = check.startsWith("docker.") ? report.containers().stream().map(c -> "container:" + c.id()).toList() : List.of("host");
        for (String asset : assets) {
            String actual = facts.get(asset + ":" + check);
            String state = !applicable || actual == null ? "점검 불가" : rule.path("expected").asText().equals(actual) ? "미발견" : "발견";
            findings.add(new Assessment.Finding(Catalog.kind(rule), rule.path("id").asText(), asset, state, rule.path("severity").asText("UNKNOWN"),
                    rule.path("title").asText(), !applicable ? "적용 플랫폼 미일치/미확인: " + platform
                    : actual == null ? "설정 값 미수집: " + check : check + "=" + actual + "; 기대값=" + rule.path("expected").asText(), rule.path("reference").asText()));
        }
    }

    public static Match matches(String version, JsonNode affected) {
        if (version == null || version.isBlank()) { return Match.UNKNOWN; }
        for (JsonNode exact : affected.path("versions")) { if (version.equals(exact.asText())) { return Match.YES; } }
        boolean understood = false, unsupported = false;
        for (JsonNode range : affected.path("ranges")) {
            if (!"ECOSYSTEM".equals(range.path("type").asText()) || !range.path("events").isArray()) { unsupported = true; continue; }
            String introduced = null;
            boolean valid = true;
            for (JsonNode event : range.path("events")) {
                if (event.has("introduced")) {
                    if (introduced != null) { valid = false; break; }
                    introduced = event.path("introduced").asText();
                } else if (introduced != null && (event.has("fixed") || event.has("last_affected") || event.has("limit"))) {
                    String end = event.has("fixed") ? event.path("fixed").asText() : event.has("limit") ? event.path("limit").asText() : event.path("last_affected").asText();
                    if (end.isBlank() || introduced.isBlank()) { valid = false; break; }
                    int cmp = compare(version, end);
                    if (("0".equals(introduced) || compare(version, introduced) >= 0) && (cmp < 0 || (event.has("last_affected") && cmp == 0))) { return Match.YES; }
                    introduced = null;
                    understood = true;
                } else { valid = false; break; }
            }
            if (valid && introduced != null && !introduced.isBlank()) {
                understood = true;
                if ("0".equals(introduced) || compare(version, introduced) >= 0) { return Match.YES; }
            }
            if (!valid) { unsupported = true; }
        }
        // OSV versions can be incomplete; absence from that list alone does not establish safety.
        return understood && !unsupported ? Match.NO : Match.UNKNOWN;
    }

    private static int compare(String a, String b) { return new ComparableVersion(a).compareTo(new ComparableVersion(b)); }
    private String reference(JsonNode record) {
        for (JsonNode link : record.path("references")) { if ("ADVISORY".equals(link.path("type").asText())) { return link.path("url").asText(); } }
        return "https://osv.dev/vulnerability/" + record.path("id").asText();
    }
    public enum Match { YES, NO, UNKNOWN }
    private record Advisory(JsonNode record, List<JsonNode> affected) { }
}
