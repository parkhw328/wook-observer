package com.wook.observer;

import com.wook.observer.model.Observation;
import com.wook.observer.model.ServiceCategory;

import java.time.Instant;
import java.util.List;
import java.util.Map;

public final class ReportFixtures {
    private ReportFixtures() { }

    public static Observation sample() {
        return new Observation("0.1.0", Instant.parse("2026-11-11T00:00:00Z"),
                new Observation.Host("fixture", "Linux", "6.1", "amd64", 4, 1024, "fixture", "29.0"),
                List.of(new Observation.ProcessInfo(123, 1, "java", "/bin/java", "service", null, 12L, 2048L, 4,
                        ServiceCategory.APPLICATION, "fixture", "abc123")),
                List.of(new Observation.JavaInfo(123, "완료", Map.of("java.version", "21.0.13"),
                        List.of(new Observation.Library("library", "1.2.3", "/app/app.jar!/lib/library.jar", "manifest", "미확인", "미확인")))),
                List.of(new Observation.ContainerInfo("abc123", "app<script>|", "app:1.0", "sha256:123", "running", 123,
                        List.of(123L), List.of("8080/tcp"), List.of("backend"), List.of(), ServiceCategory.APPLICATION, "fixture")),
                List.of(new Observation.Issue("java", "999", "권한 부족")));
    }
}
