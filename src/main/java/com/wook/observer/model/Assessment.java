package com.wook.observer.model;

import java.time.Instant;
import java.util.List;

public record Assessment(int catalogRevision, String catalogUpdatedAt, Instant evaluatedAt, String scope,
                         List<Finding> findings) {
    public record Finding(String kind, String id, String asset, String status, String severity,
                          String title, String evidence, String reference) { }

    public String status(String asset) {
        var rows = findings.stream().filter(finding -> finding.asset().equals(asset)).toList();
        if (rows.stream().anyMatch(f -> "발견".equals(f.status()))) { return "발견"; }
        if (rows.stream().anyMatch(f -> "의심".equals(f.status()))) { return "의심"; }
        if (rows.isEmpty() || rows.stream().anyMatch(f -> "점검 불가".equals(f.status()))) { return "점검 불가"; }
        return "미발견(점검 범위 내)";
    }

    public String processStatus(long pid) {
        if (findings.stream().anyMatch(f -> f.asset().startsWith("library:" + pid + ":") && "발견".equals(f.status()))) { return "발견"; }
        return status("process:" + pid);
    }

    public static String libraryAsset(long pid, Observation.Library library) { return "library:" + pid + ":" + library.path(); }
}
