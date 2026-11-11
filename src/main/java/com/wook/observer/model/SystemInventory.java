package com.wook.observer.model;

import java.util.List;

/** OS evidence and installed packages in the collector's filesystem namespace. */
public record SystemInventory(List<OsDetail> os, RpmInventory rpm) {
    public SystemInventory {
        os = os == null ? List.of() : List.copyOf(os);
        rpm = rpm == null ? new RpmInventory(Status.NOT_COLLECTED, "이 스냅샷에서는 RPM을 수집하지 않았습니다.", List.of()) : rpm;
    }

    public static SystemInventory notCollected() { return new SystemInventory(List.of(), null); }

    public enum Status {
        COLLECTED("수집 완료"), UNAVAILABLE("파일·도구 없음"), FAILED("조회 실패"),
        TIMEOUT("시간 제한"), TRUNCATED("일부 수집·제한"), NOT_COLLECTED("미수집");
        private final String label;
        Status(String label) { this.label = label; }
        public String label() { return label; }
    }

    public record OsDetail(String key, String source, Status status, String value) { }
    public record RpmInventory(Status status, String message, List<RpmPackage> packages) {
        public RpmInventory { packages = packages == null ? List.of() : List.copyOf(packages); }
    }
    public record RpmPackage(String name, String epoch, String version, String release, String architecture) {
        public String nevra() { return name + "-" + epoch + ":" + version + "-" + release + "." + architecture; }
    }
}
