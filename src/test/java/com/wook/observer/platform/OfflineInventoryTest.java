package com.wook.observer.platform;

import com.wook.observer.collector.CollectionScripts;
import com.wook.observer.collector.ServiceClassifier;
import com.wook.observer.config.ObserverProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;

import static org.assertj.core.api.Assertions.*;

class OfflineInventoryTest {
    private final OfflineInventory inventory = new OfflineInventory(new SshCollector(null, new ServiceClassifier(new ObserverProperties())));
    @TempDir Path temp;

    static String payload(String host) {
        return "HOST\t" + host + "\t6.1\tx86_64\t2\t1048576\nACCESS\t1000\toperator\nSCOPE\thost\n"
                + "PROCESS\t12345 1 operator 1024 1 nginx\nSOCKET\ttcp\tLISTEN 0 128 0.0.0.0:8080 0.0.0.0:* users:((\"nginx\",pid=12345,fd=3))\n"
                + "SOCKET_QUERY_STATUS\ttcp\t0\nSOCKET_QUERY_STATUS\tudp\t0\nRPM_STATUS\tUNAVAILABLE\nDONE\n";
    }
    static byte[] file(String payload, Instant collected) {
        return ("OBSERVER_OFFLINE\t1\nVERSION\t1.10.0\nCOLLECTED_AT\t" + collected + "\nSHA256\t"
                + OfflineInventory.sha256(payload.getBytes(StandardCharsets.UTF_8)) + "\n\n" + payload).getBytes(StandardCharsets.UTF_8);
    }
    static byte[] file(String host) { return file(payload(host), Instant.parse("2026-01-01T00:00:00Z")); }

    @Test
    void importsNewConnectionAndPortPolicyRecordsButPreservesLegacyCoverage() {
        String raw = payload("network").replace("DONE\n", "CONNECTION\ttcp\tESTAB 0 0 127.0.0.1:8443 192.0.2.1:40001\nCONNECTION_STATUS\tCOLLECTED\n"
                + "NET_SETTING\tip_local_port_range\tCOLLECTED\t40000 45000\nNET_SETTING\tip_local_reserved_ports\tCOLLECTED\t8443\nDONE\n");
        var report = inventory.parse(file(raw, Instant.now())).report();
        assertThat(report.systemInventory().connections().entries()).singleElement().satisfies(s -> {
            assertThat(s.peerAddress()).isEqualTo("192.0.2.1"); assertThat(s.peerPort()).isEqualTo(40001); assertThat(s.state()).isEqualTo("ESTABLISHED");
        });
        assertThat(report.systemInventory().networkSettings()).hasSize(2);
        assertThat(inventory.parse(file("legacy")).report().systemInventory().connections().status()).isEqualTo(com.wook.observer.model.SystemInventory.Status.NOT_COLLECTED);
    }

    @Test
    void preservesCollectionTimeScopeAndPartialEvidenceWithoutTrustingSuppliedAssessment() {
        var result = inventory.parse(file("offline-fixture"));
        assertThat(result.report().collectedAt()).isEqualTo(Instant.parse("2026-01-01T00:00:00Z"));
        assertThat(result.report().assessment()).isNull();
        assertThat(result.report().host().scope()).startsWith("파일 업로드").contains("UID 1000");
        assertThat(result.report().facts()).containsEntry("collection:method", "UPLOAD").containsEntry("collection:sha256", result.sha256());
        assertThat(result.report().systemInventory().sockets().entries()).hasSize(1);
        assertThat(result.report().issues()).anyMatch(i -> i.layer().equals("permission"));
        assertThat(result.report().javaProcesses()).isEmpty();
    }

    @Test
    void rejectsCorruptedTruncatedForeignAndOversizedFiles() {
        String original = new String(file("fixture"), StandardCharsets.UTF_8);
        for (byte[] value : new byte[][]{original.replace("fixture", "changed").getBytes(StandardCharsets.UTF_8),
                "#!/bin/sh\necho malicious".getBytes(StandardCharsets.UTF_8), "PK\u0003\u0004archive".getBytes(StandardCharsets.UTF_8),
                new byte[OfflineInventory.MAX_BYTES + 1], original.substring(0, original.length() - 4).getBytes(StandardCharsets.UTF_8),
                original.replace("OBSERVER_OFFLINE\t1", "OBSERVER_OFFLINE\t2").getBytes(StandardCharsets.UTF_8)}) {
            assertThatThrownBy(() -> inventory.parse(value)).isInstanceOf(ApiProblem.class);
        }
    }

    @Test
    void rejectsIncompleteRecordsEvenWithRecomputedChecksum() {
        String raw = payload("fixture");
        for (String value : new String[]{raw.replace("DONE\n", ""), raw + "FACT\thost:os.id\tdebian\n", raw.replace("SCOPE\thost", "SCOPE\tunknown"),
                raw.replace("ACCESS\t1000\toperator\n", ""), raw.replace("PROCESS\t12345 1 operator 1024 1 nginx", "PROCESS\tbad"),
                raw.replace("DONE\n", "HOST\tother\t6\tx86_64\t2\t1\nDONE\n"), raw.replace("DONE\n", "ASSESSMENT\tall-safe\nDONE\n"),
                raw.replace("1048576", "NaN"), raw.replace("nginx", "ng\u0000inx")}) {
            assertThatThrownBy(() -> inventory.parse(file(value, Instant.now()))).isInstanceOf(ApiProblem.class);
        }
    }

    @Test
    void rejectsFutureTimestampAndMalformedUtf8() {
        assertThatThrownBy(() -> inventory.parse(file(payload("fixture"), Instant.now().plusSeconds(601)))).isInstanceOf(ApiProblem.class).hasMessageContaining("수집 시각");
        var bytes = file("fixture"); bytes[10] = (byte) 0xFF;
        assertThatThrownBy(() -> inventory.parse(bytes)).isInstanceOf(ApiProblem.class);
    }

    @Test
    void boundsEmptyRecordsAndContainerPidListsWithoutRecursiveParsing() {
        String raw = payload("fixture");
        assertThatThrownBy(() -> inventory.parse(file(raw.replace("DONE\n", "\n".repeat(70000) + "DONE\n"), Instant.now()))).isInstanceOf(ApiProblem.class);
        String maximum = raw.replace("DONE\n", "CPIDS\tabc\t" + "1,".repeat(4096) + "\nDONE\n");
        assertThat(inventory.parse(file(maximum, Instant.now())).report()).isNotNull();
        String excessive = maximum.replace("CPIDS\tabc\t", "CPIDS\tabc\t2,");
        assertThatThrownBy(() -> inventory.parse(file(excessive, Instant.now()))).isInstanceOf(ApiProblem.class);
    }

    @Test
    void filtersJvmSecretsAndTreatsHostileTextAsData() {
        String raw = payload("<script>alert(1)</script>").replace("DONE\n", "JAVA\t12345\nJPROP\t12345\tjava.version=21.0.13\nJPROP\t12345\tpassword=must-not-leak\nDONE\n");
        var report = inventory.parse(file(raw, Instant.now())).report();
        assertThat(report.host().name()).isEqualTo("<script>alert(1)</script>");
        assertThat(report.javaProcesses().getFirst().properties()).containsOnlyKeys("java.version");
    }

    @Test
    void downloadedScriptIsSelfContainedValidPosixShellAndProvidesUsage() throws Exception {
        String script = CollectionScripts.offline();
        assertThat(script).doesNotContain("# OBSERVER_INVENTORY", "# SYSTEM_INVENTORY", "# LIBRARY_INVENTORY");
        Path file = temp.resolve("observer-collect.sh"); Files.writeString(file, script);
        var syntax = new ProcessBuilder("sh", "-n", file.toString()).redirectErrorStream(true).start();
        String output = new String(syntax.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertThat(syntax.waitFor()).as(output).isZero();
        var help = new ProcessBuilder("sh", file.toString(), "--help").start();
        assertThat(new String(help.getInputStream().readAllBytes(), StandardCharsets.UTF_8)).contains("Usage:", ".observer");
        assertThat(help.waitFor()).isZero();
    }

    @Test
    void rejectsBrokenEl7ProcessOutputBeforeLeavingAnUploadFile() throws Exception {
        String broken = payload("legacy").replaceAll("(?m)^PROCESS\\t.*$", "PROCESS\t,ppid=,user=,rss=,nlwp=,comm=");
        // A fixed inventory fixture exercises the actual wrapper completion check without collecting this host.
        String wrapper;
        try (var in = getClass().getResourceAsStream("/collector/offline-collect.sh")) {
            wrapper = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        Path output = Files.createDirectory(temp.resolve("output"));
        Path script = temp.resolve("broken-collect.sh");
        Files.writeString(script, wrapper.replace("# OBSERVER_INVENTORY", "cat <<'FIXTURE'\n" + broken + "FIXTURE\n"));
        var process = new ProcessBuilder("sh", script.toString(), output.toString()).redirectErrorStream(true).start();
        String message = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertThat(process.waitFor()).isEqualTo(2);
        assertThat(message).contains("process inventory is incomplete");
        try (var files = Files.list(output)) { assertThat(files.toList()).isEmpty(); }
        assertThatThrownBy(() -> inventory.parse(file(broken, Instant.now()))).hasMessageContaining("구형 ps", "최신 SH");
    }

    @Test
    void recoversLegacyMissingHostnameOnlyFromCompleteCollectedUnameEvidence() {
        String encoded = java.util.Base64.getEncoder().encodeToString("Linux legacy-rhel7 3.10.0-1160.el7.x86_64 #1 SMP x86_64 GNU/Linux".getBytes(StandardCharsets.UTF_8));
        String raw = payload("").replace("DONE\n", "OSINFO\tuname_all\tCOLLECTED\t" + encoded + "\nDONE\n");
        byte[] bytes = file(raw, Instant.now());
        var parsed = inventory.parse(bytes);
        assertThat(parsed.report().host().name()).isEqualTo("legacy-rhel7");
        assertThat(parsed.sha256()).isEqualTo(OfflineInventory.sha256(bytes));
        assertThat(parsed.report().facts()).containsEntry("host:identity_source", "uname_all");
        assertThat(parsed.report().issues()).anyMatch(i -> i.message().contains("uname -a"));
        assertThatThrownBy(() -> inventory.parse(file(payload(""), Instant.now()))).hasMessageContaining("호스트명");
        assertThatThrownBy(() -> inventory.parse(file(raw.replace("COLLECTED", "FAILED"), Instant.now()))).hasMessageContaining("호스트명");
        assertThatThrownBy(() -> inventory.parse(new String(bytes, StandardCharsets.UTF_8).replace(encoded, encoded + "A").getBytes(StandardCharsets.UTF_8)))
                .hasMessageContaining("무결성");
    }

    @Test
    void permitsBoundedDirectoryAccountNamesAndLegacyRpmFailuresWithoutLosingTheAsset() {
        for (String user : new String[]{"EXAMPLE\\legacy.admin", "operator@example.org", "운영계정"}) {
            String raw = payload("centos7").replace("ACCESS\t1000\toperator", "ACCESS\t1000\t" + user)
                    .replace("RPM_STATUS\tUNAVAILABLE", "RPM_STATUS\tCOLLECTED");
            var report = inventory.parse(file(raw, Instant.now())).report();
            assertThat(report.facts()).containsEntry("host:effective_user", user);
            assertThat(report.systemInventory().rpm().status()).isEqualTo(com.wook.observer.model.SystemInventory.Status.FAILED);
            assertThat(report.host().name()).isEqualTo("centos7");
        }
    }
}
