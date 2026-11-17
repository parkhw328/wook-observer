package com.wook.observer.platform;

import com.wook.observer.model.Observation;
import com.wook.observer.model.SystemInventory;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Pattern;

/** OS identity and vendor lifecycle policy, independent of vulnerability findings and subscription entitlement. */
public final class OsLifecycle {
    private OsLifecycle() { }
    private static final String VERIFIED = "2026-11-17";
    private static final LocalDate END_7 = LocalDate.of(2024, 6, 30);
    private static final String CENTOS = "https://centos.org/centos-linux/";
    private static final String RED_HAT = "https://access.redhat.com/articles/7005471";
    private static final Pattern RED_HAT_NAME = Pattern.compile("^Red Hat Enterprise Linux(?: Server| Workstation| Client)?(?: release)? ([0-9]+(?:\\.[0-9]+)*)(?:[ (].*)?$");
    private static final Pattern CENTOS_NAME = Pattern.compile("^CentOS(?: Linux)?(?: release)? ([0-9]+(?:\\.[0-9]+)*)(?:[ (].*)?$");
    public record Identity(String id, String version, String displayName, String source) { }
    public record Support(String status, boolean upgradeRequired, String label, String endedOn, String extendedUntil,
                          String note, String action, String sourceUrl, String policyVerifiedOn, String evaluatedOn) { }

    public static Identity identify(Observation report) {
        String release = "", legacy = "";
        for (var detail : report.systemInventory().os()) {
            if (detail.status() != SystemInventory.Status.COLLECTED) continue;
            if (detail.key().equals("os_release")) release = detail.value();
            if (detail.key().equals("redhat_release")) legacy = detail.value().strip();
        }
        Map<String, String> values = new HashMap<>();
        boolean conflicting = false;
        for (String line : release.split("\n")) {
            int split = line.indexOf('=');
            if (split < 1) continue;
            String key = line.substring(0, split).strip();
            if (!java.util.Set.of("ID", "VERSION_ID", "PRETTY_NAME", "NAME").contains(key)) continue;
            String value = line.substring(split + 1).strip();
            if (value.length() >= 2 && (value.startsWith("\"") && value.endsWith("\"") || value.startsWith("'") && value.endsWith("'")))
                value = value.substring(1, value.length() - 1);
            String previous = values.put(key, value);
            if (previous != null && !previous.equals(value)) conflicting = true;
        }
        String id = values.getOrDefault("ID", "");
        String version = values.getOrDefault("VERSION_ID", "");
        String display = values.getOrDefault("PRETTY_NAME", values.getOrDefault("NAME", ""));
        if (display.isBlank()) {
            display = !legacy.isBlank() ? legacy : id.isEmpty() ? report.host().os() + " " + report.host().osVersion()
                    : (id.equals("rhel") ? "Red Hat Enterprise Linux" : id.equals("centos") ? "CentOS Linux" : id) + " " + version;
        }
        if (conflicting) return new Identity("", "", display, "상충하는 /etc/os-release");
        if (!id.isEmpty()) {
            // ID_LIKE and kernel versions never identify the distribution; derivatives are not RHEL.
            if (id.equals("centos") && (display.contains("Stream") || values.getOrDefault("NAME", "").contains("Stream"))) id = "centos-stream";
            if (version.isEmpty()) {
                var fallback = fromName(legacy, "/etc/redhat-release");
                if (fallback.id().equals(id)) version = fallback.version();
            }
            return new Identity(id, version, display, "/etc/os-release");
        }
        // Older installations can omit os-release. Match only explicit distribution release strings.
        var fallback = fromName(legacy, "/etc/redhat-release");
        if (!fallback.id().isEmpty()) return fallback;
        var facts = report.facts() == null ? Map.<String, String>of() : report.facts();
        String factId = facts.getOrDefault("host:os.id", "");
        String factVersion = facts.getOrDefault("host:os.version", "");
        if (!factId.isEmpty()) return new Identity(factId, factVersion, display, "수집 OS 식별 정보");
        return new Identity("", "", display, "OS 식별 근거 부족");
    }

    /** Old saved asset summaries contain an observed display name but no structured identity. */
    public static Identity fromName(String display, String source) {
        display = display == null ? "" : display.strip();
        for (var entry : Map.of("rhel", RED_HAT_NAME, "centos", CENTOS_NAME).entrySet()) {
            var match = entry.getValue().matcher(display);
            if (match.matches()) return new Identity(entry.getKey(), match.group(1), display, source);
        }
        return new Identity("", "", display, source);
    }

    public static Support evaluate(Identity identity) { return evaluate(identity, LocalDate.now(ZoneOffset.UTC)); }
    public static Support forSummary(ServiceDiscovery.Summary summary) {
        return evaluate(summary == null ? null : summary.osIdentity() != null ? summary.osIdentity()
                : fromName(summary.distribution(), "저장된 OS 표시명"));
    }
    static Support evaluate(Identity identity, LocalDate today) {
        if (identity == null || !identity.version().matches("7(?:\\.[0-9]+)*")
                || !java.util.Set.of("rhel", "centos").contains(identity.id())) {
            return new Support("UNKNOWN", false, "지원 정책 미확인", "", "",
                    "등록된 RHEL·CentOS 7 정책으로 판정할 수 없습니다. 지원 중이라는 뜻은 아닙니다.",
                    "배포판과 버전의 공식 지원 일정을 확인하세요.", "", VERIFIED, today.toString());
        }
        boolean rhel = identity.id().equals("rhel");
        String note = rhel
                ? "정규 유지보수 종료. ELS는 별도 계약과 RHEL 7.9 조건에 따라 2029-05-31까지 제공되며, 이 서버의 계약·적용 여부는 미확인입니다."
                : "CentOS Linux 7의 공식 업데이트가 종료되었습니다. 별도 제3자 지원 계약은 판정하지 않습니다.";
        boolean ended = today.isAfter(END_7);
        return new Support(ended ? (rhel ? "MAINTENANCE_ENDED" : "END_OF_LIFE") : "SCHEDULED", ended,
                ended ? (rhel ? "정규 유지보수 종료" : "지원 종료 (EOL)") : "지원 종료 예정",
                END_7.toString(), rhel ? "2029-05-31" : "", ended ? note : "공식 종료 예정일 기준입니다. 개별 지원 계약은 확인하지 않았습니다.",
                ended ? "OS 업그레이드 필요 · 지원되는 OS로 전환 계획을 수립하세요." : "종료일 전에 OS 전환 계획을 수립하세요.",
                rhel ? RED_HAT : CENTOS, VERIFIED, today.toString());
    }
}
