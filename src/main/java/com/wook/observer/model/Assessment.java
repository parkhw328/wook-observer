package com.wook.observer.model;

import java.time.Instant;
import java.util.List;

public record Assessment(int catalogRevision, String catalogUpdatedAt, Instant evaluatedAt, String scope,
                         List<Finding> findings, List<Coverage> coverage) {
    public Assessment {
        findings = findings == null ? List.of() : List.copyOf(findings);
        coverage = coverage == null ? List.of() : List.copyOf(coverage);
    }

    // Read old snapshots without re-assessing or altering their recorded results.
    public Assessment(int revision, String updatedAt, Instant evaluatedAt, String scope, List<Finding> findings) {
        this(revision, updatedAt, evaluatedAt, scope, findings, List.of());
    }

    public record Finding(String kind, String id, String asset, String status, String severity,
                          String title, String evidence, String reference, String packageName, String version,
                          String recommendation, List<String> aliases) {
        public Finding {
            packageName = packageName == null ? "" : packageName;
            version = version == null ? "" : version;
            recommendation = recommendation == null ? "공식 권고와 서비스 영향 검토" : recommendation;
            aliases = aliases == null ? List.of() : List.copyOf(aliases);
        }
        public Finding(String kind, String id, String asset, String status, String severity,
                       String title, String evidence, String reference) {
            this(kind, id, asset, status, severity, title, evidence, reference, "", "", null, List.of());
        }
        public boolean actionable() { return "발견".equals(status) || "의심".equals(status); }
    }

    /** One library assessment or configuration rule; separate from actionable findings. */
    public record Coverage(String kind, String asset, String status, String reason, String packageName, String version) { }

    public java.util.Map<String, String> statuses() {
        var result = new java.util.HashMap<String, String>();
        coverage.forEach(c -> result.merge(c.asset(), displayStatus(c.status()), Assessment::stronger));
        findings.forEach(f -> result.merge(f.asset(), displayStatus(f.status()), Assessment::stronger));
        new java.util.HashMap<>(result).forEach((asset, status) -> {
            if (asset.startsWith("library:")) {
                int separator = asset.indexOf(':', 8);
                if (separator > 8) { result.merge("process:" + asset.substring(8, separator), status, Assessment::stronger); }
            }
        });
        return result;
    }

    private static String displayStatus(String status) {
        return "미발견".equals(status) ? "미발견(점검 범위 내)" : "점검 불가".equals(status) ? "미판정" : status;
    }

    private static String stronger(String a, String b) {
        var order = List.of("발견", "의심", "미판정", "미발견(점검 범위 내)", "비대상");
        return order.indexOf(a) <= order.indexOf(b) ? a : b;
    }

    public String status(String asset) {
        var rows = findings.stream().filter(finding -> finding.asset().equals(asset)).toList();
        if (rows.stream().anyMatch(f -> "발견".equals(f.status()))) { return "발견"; }
        if (rows.stream().anyMatch(f -> "의심".equals(f.status()))) { return "의심"; }
        var checks = coverage.stream().filter(c -> c.asset().equals(asset)).toList();
        if (!checks.isEmpty()) {
            if (checks.stream().anyMatch(c -> "미판정".equals(c.status()))) { return "미판정"; }
            if (checks.stream().anyMatch(c -> "미발견".equals(c.status()))) { return "미발견(점검 범위 내)"; }
            return "비대상";
        }
        if (rows.isEmpty() || rows.stream().anyMatch(f -> "점검 불가".equals(f.status()))) { return "미판정"; }
        return "미발견(점검 범위 내)";
    }

    public String processStatus(long pid) {
        String prefix = "library:" + pid + ":";
        if (findings.stream().anyMatch(f -> f.asset().startsWith(prefix) && "발견".equals(f.status()))) { return "발견"; }
        if (findings.stream().anyMatch(f -> f.asset().startsWith(prefix) && "의심".equals(f.status()))) { return "의심"; }
        return status("process:" + pid);
    }

    public static String libraryAsset(long pid, Observation.Library library) { return "library:" + pid + ":" + library.path(); }
}
