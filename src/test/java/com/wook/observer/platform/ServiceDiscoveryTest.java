package com.wook.observer.platform;

import com.wook.observer.ReportFixtures;
import com.wook.observer.model.Observation;
import com.wook.observer.model.ServiceCategory;
import com.wook.observer.model.SystemInventory;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ServiceDiscoveryTest {
    static Observation host(long pid, int port) {
        return new Observation("1.6.0", Instant.now(), new Observation.Host("fixture", "Linux", "6.1", "amd64", 4, 8L << 30, "host", ""),
                List.of(new Observation.ProcessInfo(pid, 1, "nginx", "/usr/sbin/nginx", "nginx", null, null, null, null,
                        ServiceCategory.WEB, "fixture", null)), List.of(), List.of(), List.of(), Map.of(), null,
                new SystemInventory(List.of(new SystemInventory.OsDetail("os_release", "fixture", SystemInventory.Status.COLLECTED, "PRETTY_NAME=\"Rocky Linux 9.6\"\n")), null,
                        new SystemInventory.SocketInventory(SystemInventory.Status.COLLECTED, "fixture", List.of(
                                new SystemInventory.Socket("TCP", "LISTEN", "::", port, List.of(new SystemInventory.SocketProcess(pid, "nginx")))))));
    }

    @Test
    void pidChangesKeepIdentityButDistinctEndpointsDoNotMerge() {
        var first = ServiceDiscovery.discover(host(100, 8080)).getFirst();
        var restarted = ServiceDiscovery.discover(host(900, 8080)).getFirst();
        assertThat(first.key()).isEqualTo(restarted.key());
        assertThat(restarted.pids()).containsExactly(900L);
        assertThat(first.bindings()).containsExactly("TCP [::]:8080");
        assertThat(first.key()).isNotEqualTo(ServiceDiscovery.discover(host(100, 9090)).getFirst().key());
        assertThat(ServiceDiscovery.summary(host(100, 8080)).distribution()).isEqualTo("Rocky Linux 9.6");
    }

    @Test
    void containersOwnTheirProcessesAndDeclaredPortsAreNotHostBindings() {
        var found = ServiceDiscovery.discover(ReportFixtures.sample());
        assertThat(found).hasSize(1);
        assertThat(found.getFirst().kind()).isEqualTo("CONTAINER");
        assertThat(found.getFirst().bindings()).containsExactly("Docker 8080/tcp");
        assertThat(found.getFirst().pids()).containsExactly(123L);
        var sample = ReportFixtures.sample();
        var recreated = new Observation("1.6.0", Instant.now(), sample.host(), List.of(), List.of(),
                List.of(new Observation.ContainerInfo("replacement-id", sample.containers().getFirst().name(), "app:2", "", "running", 800,
                        List.of(800L), List.of("8080/tcp"), List.of(), List.of(), ServiceCategory.APPLICATION, "fixture")), List.of());
        assertThat(ServiceDiscovery.discover(recreated).getFirst().key()).isEqualTo(found.getFirst().key());
    }

    @Test
    void ownerlessSocketsAndOldSnapshotsRemainExplicit() {
        var sample = host(100, 8080);
        var report = new Observation("1.6.0", Instant.now(), sample.host(), List.of(), List.of(), List.of(), List.of(), Map.of(), null,
                new SystemInventory(List.of(), null, new SystemInventory.SocketInventory(SystemInventory.Status.TRUNCATED, "partial", List.of(
                        new SystemInventory.Socket("UDP", "UNCONN", "0.0.0.0", 5353, List.of())))));
        var candidate = ServiceDiscovery.discover(report).getFirst();
        assertThat(candidate.kind()).isEqualTo("SOCKET");
        assertThat(candidate.pids()).isEmpty();
        assertThat(candidate.name()).isEqualTo("소유 프로세스 미확인");
        assertThat(ServiceDiscovery.summary(report).socketStatus()).isEqualTo("TRUNCATED");
        assertThat(ServiceDiscovery.summary(ReportFixtures.sample()).socketStatus()).isEqualTo("NOT_COLLECTED");
    }
}
