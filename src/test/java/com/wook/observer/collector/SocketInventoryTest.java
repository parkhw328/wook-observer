package com.wook.observer.collector;

import com.wook.observer.model.Observation;
import com.wook.observer.model.SystemInventory.Status;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.net.ServerSocket;
import java.net.DatagramSocket;
import java.net.InetAddress;

import static org.assertj.core.api.Assertions.assertThat;

class SocketInventoryTest {
    @Test
    void sharedShellCollectsRealListeningAndBoundSockets() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue(java.nio.file.Files.isExecutable(java.nio.file.Path.of("/usr/bin/ss"))
                || java.nio.file.Files.isExecutable(java.nio.file.Path.of("/usr/sbin/ss")));
        try (var tcp = new ServerSocket(0, 10, InetAddress.getLoopbackAddress());
             var udp = new DatagramSocket(0, InetAddress.getLoopbackAddress());
             var client = new java.net.Socket(InetAddress.getLoopbackAddress(), tcp.getLocalPort());
             var accepted = tcp.accept()) {
            var runner = new CommandRunner(new com.wook.observer.config.ObserverProperties());
            var output = runner.run(List.of("/bin/sh", "-s"), SystemInventoryCollector.script(), java.time.Duration.ofSeconds(45), 6 * 1024 * 1024);
            assertThat(output.success()).isTrue();
            var parser = new SystemInventoryParser();
            output.output().lines().forEach(parser::accept);
            var inventory = parser.build(new ArrayList<>());
            assertThat(inventory.connections().status()).isEqualTo(Status.COLLECTED);
            assertThat(inventory.connections().entries()).anyMatch(s -> s.state().equals("ESTABLISHED")
                    && s.localPort() == tcp.getLocalPort() && s.peerPort() == client.getLocalPort());
            assertThat(inventory.networkSettings()).anyMatch(s -> s.key().equals("ip_local_port_range") && s.status() == Status.COLLECTED);
            var sockets = inventory.sockets();
            assertThat(sockets.status()).isEqualTo(Status.COLLECTED);
            assertThat(sockets.entries()).anyMatch(s -> s.protocol().equals("TCP") && s.localPort() == tcp.getLocalPort()
                    && s.processes().stream().anyMatch(p -> p.pid() == ProcessHandle.current().pid()));
            assertThat(sockets.entries()).anyMatch(s -> s.protocol().equals("UDP") && s.localPort() == udp.getLocalPort());
        }
    }

    @Test
    void capturesSsAndNetstatConnectionPeersStatesAndOwnerlessTimeWait() {
        var parser = new SystemInventoryParser();
        List.of("CONNECTION\ttcp\tESTAB 0 0 [::1]:443 [::1]:52000 users:((\"nginx\",pid=42,fd=5))",
                "CONNECTION\ttcp\tTIME-WAIT 0 0 192.0.2.1:52001 192.0.2.2:443",
                "CONNECTION\ttcp-netstat\ttcp6 0 0 ::1:8443 ::1:52002 ESTABLISHED 43/java",
                "CONNECTION\ttcp-netstat\ttcp 0 0 127.0.0.1:5555 127.0.0.1:80 CLOSE_WAIT -",
                "CONNECTION_STATUS\tCOLLECTED").forEach(parser::accept);
        var result = parser.build(new ArrayList<>()).connections();
        assertThat(result.status()).isEqualTo(Status.COLLECTED);
        assertThat(result.entries()).hasSize(4).anySatisfy(s -> {
            assertThat(s.state()).isEqualTo("ESTABLISHED"); assertThat(s.peerAddress()).isEqualTo("::1");
            assertThat(s.peerPort()).isEqualTo(52002); assertThat(s.source()).isEqualTo("netstat");
            assertThat(s.processes().getFirst().pid()).isEqualTo(43);
        }).anySatisfy(s -> { assertThat(s.state()).isEqualTo("TIME_WAIT"); assertThat(s.processes()).isEmpty(); });
        assertThat(parser.build(new ArrayList<>()).sockets().entries()).isEmpty();
    }

    @Test
    void independentlyBoundsConnectionsAndDistinguishesMissingAndPartialCollections() {
        var parser = new SystemInventoryParser();
        parser.accept("SOCKET\ttcp\tLISTEN 0 0 *:443 *:*");
        parser.accept("SOCKET_QUERY_STATUS\ttcp\t0"); parser.accept("SOCKET_QUERY_STATUS\tudp\t0");
        for (int i = 0; i < 4097; i++) parser.accept("CONNECTION\ttcp\tESTAB 0 0 127.0.0.1:443 192.0.2.1:" + (10000 + i));
        parser.accept("CONNECTION_STATUS\tCOLLECTED");
        var result = parser.build(new ArrayList<>());
        assertThat(result.sockets().status()).isEqualTo(Status.COLLECTED);
        assertThat(result.connections().status()).isEqualTo(Status.TRUNCATED);
        assertThat(result.connections().entries()).hasSize(4096);
        assertThat(new SystemInventoryParser().build(new ArrayList<>()).connections().status()).isEqualTo(Status.NOT_COLLECTED);
        for (String status : List.of("TIMEOUT", "UNAVAILABLE", "FAILED")) {
            var limited = new SystemInventoryParser(); limited.accept("CONNECTION_STATUS\t" + status);
            assertThat(limited.build(new ArrayList<>()).connections().status()).isEqualTo(Status.valueOf(status));
        }
    }

    @Test
    void parsesNetstatBindingsAndRejectsMalformedPeersAndConflictingPortPolicy() {
        var parser = new SystemInventoryParser();
        List.of("SOCKET\ttcp-netstat\ttcp 0 0 0.0.0.0:22 0.0.0.0:* LISTEN 10/sshd",
                "SOCKET\tudp-netstat\tudp6 0 0 :::53 :::* -", "SOCKET_QUERY_STATUS\ttcp\t0", "SOCKET_QUERY_STATUS\tudp\t0",
                "CONNECTION\ttcp\tESTAB 0 0 *:443 broken-peer", "CONNECTION_STATUS\tCOLLECTED",
                "NET_SETTING\tip_local_port_range\tCOLLECTED\t32768 60999", "NET_SETTING\tip_local_reserved_ports\tCOLLECTED\t8080,9000-9100",
                "NET_SETTING\tip_local_reserved_ports\tCOLLECTED\t$(touch /tmp/not-executed)").forEach(parser::accept);
        var result = parser.build(new ArrayList<>());
        assertThat(result.sockets().status()).isEqualTo(Status.COLLECTED);
        assertThat(result.sockets().entries()).hasSize(2);
        assertThat(result.connections().status()).isEqualTo(Status.TRUNCATED);
        assertThat(result.networkSettings()).anyMatch(s -> s.key().equals("ip_local_reserved_ports") && s.status() == Status.FAILED);
    }

    @Test
    void preservesSeparateSocketsSharingAnEndpointAndAcceptsWhitespaceInSsOutput() {
        var parser = new SystemInventoryParser();
        parser.accept("SOCKET\tudp\tudp\tUNCONN\t0\t0\t*:5353\t*:*");
        parser.accept("SOCKET\tudp\tUNCONN 0 0 *:5353 *:*");
        parser.accept("SOCKET_QUERY_STATUS\ttcp\t0"); parser.accept("SOCKET_QUERY_STATUS\tudp\t0");
        var result = parser.build(new ArrayList<>()).sockets();
        assertThat(result.status()).isEqualTo(Status.COLLECTED);
        assertThat(result.entries()).hasSize(2);
    }

    @Test
    void preservesIpv4Ipv6UdpAndMultipleProcessOwnersWithoutInventingReachability() {
        var parser = new SystemInventoryParser();
        List.of(
                "SOCKET\ttcp\tLISTEN 0 4096 127.0.0.1:5432 0.0.0.0:* users:((\"postgres\",pid=42,fd=5))",
                "SOCKET\ttcp\tLISTEN 0 128 [::]:8080 [::]:* users:((\"java\",pid=123,fd=7),(\"java\",pid=124,fd=7))",
                "SOCKET\tudp\tUNCONN 0 0 0.0.0.0:53 0.0.0.0:*",
                "SOCKET\tudp\tESTAB 0 0 [fe80::1%eth0]:5300 [fe80::2%eth0]:53 users:((\"dns-client\",pid=75,fd=3))",
                "SOCKET_QUERY_STATUS\ttcp\t0", "SOCKET_QUERY_STATUS\tudp\t0").forEach(parser::accept);
        var result = parser.build(new ArrayList<>()).sockets();
        assertThat(result.status()).isEqualTo(Status.COLLECTED);
        assertThat(result.entries()).hasSize(4).anySatisfy(s -> {
            assertThat(s.localAddress()).isEqualTo("::");
            assertThat(s.localPort()).isEqualTo(8080);
            assertThat(s.processes()).extracting(p -> p.pid()).containsExactly(123L, 124L);
        }).anySatisfy(s -> {
            assertThat(s.localPort()).isEqualTo(53);
            assertThat(s.protocol()).isEqualTo("UDP");
            assertThat(s.processes()).isEmpty();
        }).anySatisfy(s -> assertThat(s.localAddress()).isEqualTo("fe80::1%eth0"));
    }

    @Test
    void preservesPartialResultsAndNeverTreatsTimeoutFailureOrLimitsAsEmptySuccess() {
        for (String end : List.of("SOCKET_QUERY_STATUS\ttcp\t124", "SOCKET_QUERY_STATUS\ttcp\t1",
                "SOCKET_QUERY_STATUS\ttcp\t0", "SOCKET_QUERY_STATUS\ttcp\t0\nSOCKET_QUERY_STATUS\tudp\t0\nSOCKET_STATUS\tTRUNCATED")) {
            var parser = new SystemInventoryParser();
            parser.accept("SOCKET\ttcp\tLISTEN 0 10 *:22 *:*");
            end.lines().forEach(parser::accept);
            var issues = new ArrayList<Observation.Issue>();
            var result = parser.build(issues).sockets();
            assertThat(result.status()).isNotEqualTo(Status.COLLECTED);
            assertThat(result.entries()).hasSize(1);
            assertThat(issues).anyMatch(i -> i.layer().equals("network"));
        }
        var parser = new SystemInventoryParser();
        parser.accept("SOCKET_STATUS\tUNAVAILABLE");
        assertThat(parser.build(new ArrayList<>()).sockets().status()).isEqualTo(Status.UNAVAILABLE);
    }

    @Test
    void rejectsMalformedEndpointsAndBoundsSocketCount() {
        var parser = new SystemInventoryParser();
        for (String endpoint : List.of("address", "*:65536", "*:not-a-port", "*:0")) {
            parser.accept("SOCKET\ttcp\tLISTEN 0 10 " + endpoint + " *:*");
        }
        for (int port = 1; port <= 4097; port++) { parser.accept("SOCKET\ttcp\tLISTEN 0 10 *:" + port + " *:*"); }
        parser.accept("SOCKET_QUERY_STATUS\ttcp\t0"); parser.accept("SOCKET_QUERY_STATUS\tudp\t0");
        var result = parser.build(new ArrayList<>()).sockets();
        assertThat(result.entries()).hasSize(4096);
        assertThat(result.status()).isEqualTo(Status.TRUNCATED);
    }
}
