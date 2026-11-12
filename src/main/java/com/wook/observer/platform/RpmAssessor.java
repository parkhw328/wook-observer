package com.wook.observer.platform;

import com.wook.observer.model.Assessment;
import com.wook.observer.model.Observation;
import com.wook.observer.model.SystemInventory;
import com.wook.observer.model.SystemInventory.RpmPackage;
import tools.jackson.databind.JsonNode;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** Distro-specific RPM advisories. Unresolved identity, module or stream is never a safe result. */
public final class RpmAssessor {
    private static final Pattern SOURCE = Pattern.compile("(.+)-([^-]+)-([^-]+)\\.(?:src|nosrc)\\.rpm");
    private static final Pattern CVE = Pattern.compile("CVE-[0-9]{4}-[0-9]{4,}");
    private static final Pattern RHEL = Pattern.compile("Red Hat:(enterprise_linux|enterprise_linux_eus|rhel_(?:eus|aus|e4s|tus)):(8|9|10)(?:\\.[0-9]+)?(?::.*)?");

    static java.util.function.Predicate<JsonNode> selection(Observation report) {
        Platform platform = platform(report);
        Set<String> names = new LinkedHashSet<>();
        for (RpmPackage installed : report.systemInventory().rpm().packages()) {
            names.add(installed.name());
            String source = sourceName(installed);
            if (!source.isEmpty()) { names.add(source); }
        }
        return affected -> names.contains(affected.path("package").path("name").asText())
                && sameDistribution(affected.path("package").path("ecosystem").asText(), platform);
    }

    public static void assess(Observation report, List<JsonNode> records, List<Assessment.Finding> findings,
                              List<Assessment.Coverage> coverage) {
        var inventory = report.systemInventory().rpm();
        Platform platform = platform(report);
        if (inventory.status() != SystemInventory.Status.COLLECTED) {
            coverage.add(new Assessment.Coverage("RPM", "host:rpm", "미판정", "RPM 목록 " + inventory.status().label(), "", ""));
        }
        if (inventory.packages().isEmpty()) { return; }
        Map<String, List<Entry>> index = new HashMap<>();
        Set<String> rhelEcosystems = RedHatRepositories.ecosystems(inventory, platform.version());
        for (JsonNode record : records) {
            if (record.hasNonNull("withdrawn") || !"CVE".equals(Catalog.kind(record)) || !securityRecord(record, platform.id())) { continue; }
            for (JsonNode affected : record.path("affected")) {
                String ecosystem = affected.path("package").path("ecosystem").asText();
                if (!sameDistribution(ecosystem, platform)) { continue; }
                boolean applicable = !platform.id().equals("rhel") || rhelEcosystems.contains(ecosystem);
                // Known other RHEL streams are outside scope. Unknown mapping remains explicitly unassessed.
                if (!applicable && !rhelEcosystems.isEmpty()) { continue; }
                index.computeIfAbsent(affected.path("package").path("name").asText(), k -> new ArrayList<>())
                        .add(new Entry(record, affected, applicable));
            }
        }
        for (RpmPackage installed : inventory.packages().stream().distinct().toList()) {
            String invalid = identityProblem(installed, platform);
            if (!invalid.isEmpty()) { coverage.add(coverage(installed, "미판정", invalid)); continue; }
            String sourceName = sourceName(installed);
            var candidates = new ArrayList<>(index.getOrDefault(installed.name(), List.of()));
            if (!sourceName.isEmpty() && !sourceName.equals(installed.name())) { candidates.addAll(index.getOrDefault(sourceName, List.of())); }
            if (candidates.isEmpty()) { coverage.add(coverage(installed, "미판정", "배포판·패키지에 해당하는 RPM 권고 없음")); continue; }
            Map<String, List<Entry>> byAdvisory = new LinkedHashMap<>();
            candidates.forEach(e -> byAdvisory.computeIfAbsent(e.record().path("id").asText(), k -> new ArrayList<>()).add(e));
            boolean found = false, suspected = false, compared = false, unknown = false;
            Set<String> reasons = new LinkedHashSet<>();
            for (var entries : byAdvisory.values()) {
                var direct = entries.stream().filter(e -> e.affected().path("package").path("name").asText().equals(installed.name())).toList();
                boolean sourceOnly = direct.isEmpty();
                var selected = sourceOnly ? entries : direct;
                List<Result> results = new ArrayList<>();
                for (Entry entry : selected) {
                    Result result = entry.applicable() ? evaluate(installed, entry.affected(), platform)
                            : Result.unknown("RHEL 저장소·제품 스트림/CPE 매핑 미확인");
                    results.add(result);
                }
                boolean match = results.stream().anyMatch(r -> r.match() == Match.YES);
                if (results.stream().anyMatch(r -> r.match() == Match.UNKNOWN)) {
                    unknown = true;
                    results.stream().filter(r -> r.match() == Match.UNKNOWN).forEach(r -> reasons.add(r.reason()));
                }
                if (sourceOnly && !match) {
                    unknown = true; reasons.add("소스 RPM 대응만 확인: 바이너리/소스 Epoch 검토 필요");
                } else if (results.stream().anyMatch(r -> r.match() == Match.NO)) { compared = true; }
                if (!match) { continue; }
                if (sourceOnly) { suspected = true; } else { found = true; }
                var record = selected.getFirst().record();
                var matched = results.stream().filter(r -> r.match() == Match.YES).toList();
                String fixed = String.join(", ", matched.stream().flatMap(r -> r.fixed().stream()).distinct().toList());
                String ecosystem = selected.getFirst().affected().path("package").path("ecosystem").asText();
                String evidence = "설치 " + installed.nevra() + "; OS=" + platform.id() + ":" + platform.version()
                        + "; 권고 생태계=" + ecosystem + "; 공급자=" + installed.vendor() + "; "
                        + String.join("; ", matched.stream().flatMap(r -> r.evidence().stream()).distinct().toList());
                if (sourceOnly) { evidence += "; SOURCERPM=" + installed.sourceRpm() + "; 소스 대응 후보이며 소스 Epoch/바이너리 영향 확인 필요"; }
                String action = (sourceOnly ? "소스 RPM과 바이너리 영향·Epoch 확인 후 " : "")
                        + (fixed.isEmpty() ? "공식 권고의 수정 패키지/완화책 확인" : "해당 영향 구간 수정 EVR: " + fixed)
                        + "; 동일 배포판·지원 스트림·아키텍처의 패키지 적용 검토";
                if (installed.name().startsWith("kernel")) { action += "; 설치 커널과 실행 커널을 구분하고 재부팅 필요 여부 확인"; }
                findings.add(new Assessment.Finding("RPM", record.path("id").asText(), Assessment.rpmAsset(installed), sourceOnly ? "의심" : "발견",
                        severity(record), record.path("summary").asText(record.path("id").asText()), evidence,
                        reference(record), installed.name(), installed.evr(), action, texts(record.path("aliases")), related(record)));
            }
            String state = found ? "발견" : suspected ? "의심" : unknown ? "미판정" : compared ? "미발견" : "비대상";
            coverage.add(coverage(installed, state, found ? "RPM EVR 영향 범위 일치" : suspected ? "소스 RPM 기준 영향 후보"
                    : unknown ? String.join("; ", reasons) : compared ? "등록된 RPM 영향 범위 밖" : "권고 아키텍처 불일치"));
            if ((found || suspected) && unknown) { coverage.add(coverage(installed, "미판정", String.join("; ", reasons))); }
        }
    }

    private static Assessment.Coverage coverage(RpmPackage installed, String state, String reason) {
        return new Assessment.Coverage("RPM", Assessment.rpmAsset(installed), state, reason, installed.name(), installed.evr());
    }

    private static String identityProblem(RpmPackage installed, Platform platform) {
        if (!Set.of("rhel", "rocky", "almalinux").contains(platform.id()) || !platform.version().matches("(?:8|9|10)(?:\\.[0-9]+)?")) {
            return "지원 OS/버전 미확인: RHEL·Rocky·AlmaLinux 8/9/10 필요";
        }
        try {
            if (installed.epoch() == null || !installed.epoch().matches("[0-9]{1,30}") || installed.release() == null || installed.release().isBlank()) {
                return "RPM Epoch/Version/Release 미확인";
            }
            RpmVersion.parse(installed.evr());
        } catch (IllegalArgumentException e) { return "RPM Epoch/Version/Release 형식 미지원"; }
        if (installed.architecture() == null || !installed.architecture().matches("x86_64|aarch64|ppc64le|s390x|i[3-6]86|noarch")) { return "RPM 아키텍처 미확인/미지원"; }
        String vendor = installed.vendor().toLowerCase(Locale.ROOT);
        boolean vendorMatches = switch (platform.id()) {
            case "rhel" -> vendor.equals("red hat, inc.");
            case "rocky" -> vendor.equals("rocky enterprise software foundation") || vendor.equals("rocky linux");
            case "almalinux" -> vendor.equals("almalinux") || vendor.equals("almalinux os foundation");
            default -> false;
        };
        if (!vendorMatches) { return "배포판 공식 RPM 공급자 미확인/외부 패키지"; }
        if (!installed.modularityLabel().isBlank() || installed.release().contains("module")) { return "모듈 RPM: 공식 OSV의 모듈 스트림 대응 정보 부족"; }
        return "";
    }

    private static String sourceName(RpmPackage installed) {
        var match = SOURCE.matcher(installed.sourceRpm());
        return match.matches() && match.group(2).equals(installed.version()) && match.group(3).equals(installed.release()) ? match.group(1) : "";
    }

    private static boolean securityRecord(JsonNode record, String distribution) {
        String prefix = switch (distribution) { case "rhel" -> "RHSA"; case "rocky" -> "(?:RLSA|RXSA)"; case "almalinux" -> "ALSA"; default -> "(?!)"; };
        return record.path("id").asText().matches(prefix + "-[0-9]{4}:[A-Za-z0-9]+") ;
    }

    private static boolean sameDistribution(String ecosystem, Platform platform) {
        String major = platform.version().split("\\.")[0];
        if (platform.id().equals("rocky")) { return ecosystem.equals("Rocky Linux:" + major); }
        if (platform.id().equals("almalinux")) { return ecosystem.equals("AlmaLinux:" + major); }
        var match = RHEL.matcher(ecosystem);
        return platform.id().equals("rhel") && match.matches() && match.group(2).equals(major);
    }

    private static Platform platform(Observation report) {
        Map<String, String> values = new HashMap<>();
        for (var detail : report.systemInventory().os()) {
            if (!detail.key().equals("os_release") || detail.status() != SystemInventory.Status.COLLECTED) { continue; }
            for (String line : detail.value().split("\n")) {
                int separator = line.indexOf('=');
                if (separator < 0) { continue; }
                String key = line.substring(0, separator).strip(), value = line.substring(separator + 1).strip();
                if (value.length() > 1 && ((value.startsWith("\"") && value.endsWith("\"")) || (value.startsWith("'") && value.endsWith("'")))) {
                    value = value.substring(1, value.length() - 1);
                }
                if (key.equals("ID") || key.equals("VERSION_ID")) { values.put(key, value); }
            }
        }
        var facts = report.facts() == null ? Map.<String, String>of() : report.facts();
        return new Platform(values.getOrDefault("ID", facts.getOrDefault("host:os.id", "")),
                values.getOrDefault("VERSION_ID", facts.getOrDefault("host:os.version", "")));
    }

    static Result evaluate(RpmPackage installed, JsonNode affected, Platform platform) {
        try {
            String purl = affected.path("package").path("purl").asText();
            if (!purl.isEmpty() && (!purl.startsWith("pkg:rpm/") || purl.contains("#"))) { return Result.unknown("RPM purl 형식 미지원"); }
            if (purl.contains("?")) {
                Set<String> qualifiers = new LinkedHashSet<>();
                for (String part : purl.substring(purl.indexOf('?') + 1).split("&")) {
                    String[] keyValue = part.split("=", 2);
                    if (keyValue.length != 2 || !qualifiers.add(keyValue[0])) { return Result.unknown("RPM purl 해석 불가"); }
                    String value = URLDecoder.decode(keyValue[1].replace("+", "%2B"), StandardCharsets.UTF_8);
                    if (keyValue[0].equals("epoch") && !value.matches("[0-9]{1,30}")) { return Result.unknown("RPM purl Epoch 형식 미지원"); }
                    if (keyValue[0].equals("arch") && !value.equals(installed.architecture())) { return Result.out(); }
                    if (keyValue[0].equals("distro") && !value.equals((platform.id().equals("rocky") ? "rocky-linux" : platform.id()) + "-" + platform.version().split("\\.")[0])) {
                        return Result.unknown("RPM purl의 배포판 세부 분기 미지원");
                    }
                    if (!Set.of("arch", "distro", "epoch").contains(keyValue[0])) { return Result.unknown("RPM purl 추가 제약 미지원"); }
                }
            }
            JsonNode architectures = affected.path("ecosystem_specific").path("architectures");
            if (!architectures.isMissingNode() && (!architectures.isArray() || architectures.isEmpty())) { return Result.unknown("RPM 아키텍처 제약 형식 미지원"); }
            if (architectures.isArray() && texts(architectures).stream().noneMatch(installed.architecture()::equals)) { return Result.out(); }
            RpmVersion version = RpmVersion.parse(installed.evr());
            List<String> evidence = new ArrayList<>(), fixed = new ArrayList<>();
            boolean understood = false, unsupported = false;
            if (affected.has("versions") && !affected.path("versions").isArray()) { return Result.unknown("RPM 영향 버전 목록 형식 미지원"); }
            for (JsonNode item : affected.path("versions")) {
                if (!item.isTextual()) { unsupported = true; continue; }
                if (version.compareTo(boundary(item.asText(), platform)) == 0) { evidence.add("명시적 영향 EVR " + item.asText()); }
                understood = true;
            }
            for (JsonNode range : affected.path("ranges")) {
                if (!"ECOSYSTEM".equals(range.path("type").asText()) || !range.path("events").isArray() || range.path("events").isEmpty()) { unsupported = true; continue; }
                List<Interval> intervals = new ArrayList<>();
                String start = null;
                boolean valid = true;
                for (JsonNode event : range.path("events")) {
                    if (event.size() != 1) { valid = false; break; }
                    if (event.has("introduced") && start == null && event.path("introduced").isTextual()) {
                        start = event.path("introduced").asText();
                        if (!"0".equals(start)) { boundary(start, platform); }
                    } else if (start != null && (event.has("fixed") || event.has("last_affected") || event.has("limit"))) {
                        String key = event.has("fixed") ? "fixed" : event.has("last_affected") ? "last_affected" : "limit";
                        String end = event.path(key).asText();
                        if (!event.path(key).isTextual() || (!"0".equals(start) && boundary(start, platform).compareTo(boundary(end, platform)) > 0)) { valid = false; break; }
                        boundary(end, platform);
                        intervals.add(new Interval(start, end, key)); start = null;
                    } else { valid = false; break; }
                }
                if (!valid) { unsupported = true; continue; }
                if (start != null) { intervals.add(new Interval(start, "", "")); }
                understood = true;
                for (Interval interval : intervals) {
                    if (!interval.start().equals("0") && version.compareTo(RpmVersion.parse(interval.start())) < 0) { continue; }
                    int compare = interval.end().isEmpty() ? -1 : version.compareTo(RpmVersion.parse(interval.end()));
                    if (!interval.end().isEmpty() && (compare > 0 || compare == 0 && !interval.key().equals("last_affected"))) { continue; }
                    evidence.add("RPM EVR " + (interval.start().equals("0") ? "이전 버전 전체" : ">= " + interval.start())
                            + (interval.end().isEmpty() ? " (상한 없음)" : (interval.key().equals("last_affected") ? " ~ <= " : " ~ < ") + interval.end()));
                    if (interval.key().equals("fixed")) { fixed.add(interval.end()); }
                }
            }
            if (!evidence.isEmpty()) { return new Result(Match.YES, fixed, evidence, ""); }
            return understood && !unsupported ? new Result(Match.NO, List.of(), List.of(), "") : Result.unknown("RPM 버전 범위 미지원/불완전");
        } catch (IllegalArgumentException exception) { return Result.unknown("RPM 버전·모듈·배포판 분기 해석 불가"); }
    }

    private static RpmVersion boundary(String value, Platform platform) {
        var version = RpmVersion.parse(value);
        if (version.release().isEmpty() || version.release().contains("module")) { throw new IllegalArgumentException(); }
        var major = Pattern.compile("(?:^|[._+])el(8|9|10)(?:[._+]|$)").matcher(version.release());
        if (major.find() && !major.group(1).equals(platform.version().split("\\.")[0])) { throw new IllegalArgumentException(); }
        return version;
    }

    private static List<String> related(JsonNode record) {
        Set<String> ids = new LinkedHashSet<>();
        for (String field : List.of("upstream", "related")) { texts(record.path(field)).stream().filter(v -> CVE.matcher(v).matches()).forEach(ids::add); }
        for (JsonNode reference : record.path("references")) {
            var match = CVE.matcher(reference.path("url").asText());
            while (match.find()) { ids.add(match.group()); }
        }
        return ids.stream().sorted().toList();
    }

    private static List<String> texts(JsonNode node) { return node.isArray() ? node.valueStream().filter(JsonNode::isTextual).map(JsonNode::asText).distinct().toList() : List.of(); }

    private static String severity(JsonNode record) {
        String value = record.path("database_specific").path("severity").asText().toUpperCase(Locale.ROOT);
        if (Set.of("CRITICAL", "HIGH", "MODERATE", "MEDIUM", "LOW").contains(value)) { return value; }
        String summary = record.path("summary").asText().toLowerCase(Locale.ROOT);
        for (var entry : Map.of("critical", "CRITICAL", "important", "HIGH", "moderate", "MEDIUM", "low", "LOW").entrySet()) {
            if (summary.startsWith(entry.getKey() + ":") || record.path("references").valueStream().anyMatch(r ->
                    r.path("url").asText().equals("https://access.redhat.com/security/updates/classification/#" + entry.getKey()))) { return entry.getValue(); }
        }
        return "UNKNOWN";
    }

    private static String reference(JsonNode record) {
        for (JsonNode link : record.path("references")) { if (link.path("type").asText().equals("ADVISORY")) { return link.path("url").asText(); } }
        return "https://osv.dev/vulnerability/" + record.path("id").asText();
    }

    enum Match { YES, NO, UNKNOWN, OUT }
    record Platform(String id, String version) { }
    record Result(Match match, List<String> fixed, List<String> evidence, String reason) {
        static Result unknown(String reason) { return new Result(Match.UNKNOWN, List.of(), List.of(), reason); }
        static Result out() { return new Result(Match.OUT, List.of(), List.of(), ""); }
    }
    private record Entry(JsonNode record, JsonNode affected, boolean applicable) { }
    private record Interval(String start, String end, String key) { }
    private RpmAssessor() { }
}
