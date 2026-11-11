package com.wook.observer.collector;

import org.junit.jupiter.api.Test;
import org.springframework.boot.info.BuildProperties;

import java.util.List;
import java.util.Properties;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ObservationServiceTest {
    @Test
    void continuesAfterCollectorFailureAndRecordsIt() {
        var docker = mock(DockerCollector.class);
        when(docker.collect(anyList())).thenThrow(new IllegalStateException("private detail"));
        var processes = mock(ProcessCollector.class);
        when(processes.collect(anyList(), anyList())).thenReturn(List.of());
        var java = mock(JavaCollector.class);
        when(java.collect(anyList(), anyList())).thenReturn(List.of());
        var report = new ObservationService(docker, processes, java, build()).collect();
        assertThat(report.issues()).anySatisfy(issue -> assertThat(issue.message()).contains("Docker 수집 실패").doesNotContain("private detail"));
        assertThat(report.processes()).isEmpty();
    }

    @Test
    void rejectsOverlappingScansAndReleasesLockAfterCompletion() throws Exception {
        var docker = mock(DockerCollector.class);
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        when(docker.collect(anyList())).thenAnswer(invocation -> {
            entered.countDown();
            release.await(3, TimeUnit.SECONDS);
            return new DockerCollector.Result("fixture", List.of());
        });
        var processes = mock(ProcessCollector.class);
        when(processes.collect(anyList(), anyList())).thenReturn(List.of());
        var java = mock(JavaCollector.class);
        when(java.collect(anyList(), anyList())).thenReturn(List.of());
        var service = new ObservationService(docker, processes, java, build());
        try (var executor = Executors.newSingleThreadExecutor()) {
            var first = executor.submit(service::collect);
            try {
                assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
                assertThatThrownBy(service::collect).isInstanceOf(ObservationService.ScanInProgressException.class);
            } finally {
                release.countDown();
            }
            assertThat(first.get(3, TimeUnit.SECONDS)).isNotNull();
        }
        assertThat(service.collect()).isNotNull();
    }

    private static BuildProperties build() {
        Properties properties = new Properties();
        properties.setProperty("version", "0.1.0");
        return new BuildProperties(properties);
    }
}
