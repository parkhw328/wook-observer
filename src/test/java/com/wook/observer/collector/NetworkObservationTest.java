package com.wook.observer.collector;

import com.wook.observer.model.*;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import java.time.Instant;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class NetworkObservationTest {
    @Test
    void usesKernelPolicyAndOwnerEvidenceWithoutTreatingRootOrLowPortsAsSystemServices() {
        var parser = new SystemInventoryParser();
        List.of("CONNECTION\ttcp\tESTAB 0 0 127.0.0.1:22 192.0.2.2:40000 users:((\"sshd\",pid=1,fd=4))",
                "CONNECTION\ttcp\tESTAB 0 0 127.0.0.1:8080 192.0.2.2:40001 users:((\"sshd\",pid=2,fd=4))",
                "CONNECTION\ttcp\tESTAB 0 0 127.0.0.1:40002 192.0.2.2:443 users:((\"sshd\",pid=3,fd=4))",
                "CONNECTION\ttcp\tTIME-WAIT 0 0 127.0.0.1:40003 192.0.2.2:443",
                "CONNECTION_STATUS\tCOLLECTED", "NET_SETTING\tip_local_port_range\tCOLLECTED\t40000 45000",
                "NET_SETTING\tip_local_reserved_ports\tCOLLECTED\t8080,9000-9100").forEach(parser::accept);
        var processes = List.of(process(1,"/usr/sbin/sshd",null),process(2,"/APP/sshd",null),process(3,"/usr/sbin/sshd","container-id"));
        var report = new Observation("1.12.0", Instant.now(), null, processes, List.of(), List.of(), List.of(), Map.of(), null, parser.build(new ArrayList<>()));
        var network = NetworkObservation.from(report);
        assertThat(network.entries()).hasSize(4);
        assertThat(network.entries().stream().filter(NetworkObservation.Entry::systemService)).hasSize(1);
        assertThat(network.entries()).anySatisfy(e -> {
            assertThat(e.socket().localPort()).isEqualTo(8080); assertThat(e.reservedPort()).isTrue(); assertThat(e.systemService()).isFalse();
        }).anySatisfy(e -> { assertThat(e.socket().localPort()).isEqualTo(40002); assertThat(e.ephemeralRange()).isTrue(); assertThat(e.systemService()).isFalse(); });
    }
    private static Observation.ProcessInfo process(long pid, String executable, String container) {
        return new Observation.ProcessInfo(pid,0,"sshd",executable,"root",null,null,null,null,ServiceCategory.INFRASTRUCTURE,"fixture",container);
    }
    @Test
    void legacyJsonRetainsUnknownPeerAndConnectionCoverage() {
        String json = "{\"os\":[],\"rpm\":null,\"sockets\":{\"status\":\"COLLECTED\",\"message\":\"legacy\",\"entries\":[{\"protocol\":\"TCP\",\"state\":\"LISTEN\",\"localAddress\":\"::\",\"localPort\":443,\"processes\":[]}]}}";
        var inventory = JsonMapper.builder().build().readValue(json,SystemInventory.class);
        assertThat(inventory.connections().status()).isEqualTo(SystemInventory.Status.NOT_COLLECTED);
        assertThat(inventory.networkSettings()).isEmpty();
        assertThat(inventory.sockets().entries().getFirst().peerAddress()).isEmpty();
        assertThat(inventory.sockets().entries().getFirst().peerPort()).isNull();
    }
}
