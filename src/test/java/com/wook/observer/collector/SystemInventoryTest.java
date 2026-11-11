package com.wook.observer.collector;

import com.wook.observer.ReportFixtures;
import com.wook.observer.config.ObserverProperties;
import com.wook.observer.model.Observation;
import com.wook.observer.model.SystemInventory;
import com.wook.observer.model.SystemInventory.Status;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SystemInventoryTest {
    @TempDir Path temporary;

    @Test
    void keepsDistributionKernelAndEachInstalledPackageVersionSeparate() {
        var parser = new SystemInventoryParser();
        parser.accept(os("os_release", "ID=rhel\nVERSION_ID=9.6\n"));
        parser.accept(os("kernel_release", "5.14.0-570.el9.x86_64"));
        parser.accept("RPM\tkernel\t(none)\t5.14.0\t570.el9\tx86_64");
        parser.accept("RPM\tkernel\t0\t5.14.0\t571.el9\tx86_64");
        parser.accept("RPM\tglibc\t2\t2.34\t168.el9\ti686");
        parser.accept("RPM\tglibc\t2\t2.34\t168.el9\tx86_64");
        parser.accept("RPM_STATUS\tCOLLECTED");
        var inventory = parser.build(new ArrayList<>());
        assertThat(inventory.os()).anyMatch(d -> d.key().equals("os_release") && d.value().contains("VERSION_ID=9.6"));
        assertThat(inventory.os()).anyMatch(d -> d.key().equals("kernel_release") && d.value().startsWith("5.14.0"));
        assertThat(inventory.rpm().status()).isEqualTo(Status.COLLECTED);
        assertThat(inventory.rpm().packages()).hasSize(4).anyMatch(p -> p.nevra().equals("kernel-0:5.14.0-570.el9.x86_64"));
    }

    @Test
    void distinguishesUnavailableFailureTimeoutAndSuccessfulEmptyDatabase() {
        for (Status status : List.of(Status.UNAVAILABLE, Status.FAILED, Status.TIMEOUT, Status.COLLECTED)) {
            var parser = new SystemInventoryParser();
            parser.accept("RPM_STATUS\t" + status);
            var issues = new ArrayList<Observation.Issue>();
            var inventory = parser.build(issues);
            assertThat(inventory.rpm().status()).isEqualTo(status);
            assertThat(inventory.rpm().packages()).isEmpty();
            assertThat(issues.stream().anyMatch(i -> i.layer().equals("rpm"))).isEqualTo(status != Status.COLLECTED);
        }
    }

    @Test
    void neverTreatsPartialOrMalformedRpmOutputAsComplete() {
        for (String ending : List.of("", "RPM_STATUS\tTRUNCATED", "RPM\tbroken\nRPM_STATUS\tCOLLECTED")) {
            var parser = new SystemInventoryParser();
            parser.accept("RPM\tvalid\t0\t1.2\t3.el9\tx86_64");
            ending.lines().forEach(parser::accept);
            var result = parser.build(new ArrayList<>());
            assertThat(result.rpm().status()).isEqualTo(Status.TRUNCATED);
            assertThat(result.rpm().packages()).hasSize(1);
        }
    }

    @Test
    void boundsPackageCountAndRejectsInvalidOrOversizeOsEvidence() {
        var parser = new SystemInventoryParser();
        for (int i = 0; i <= SystemInventoryParser.MAX_PACKAGES; i++) {
            parser.accept("RPM\tpkg-" + i + "\t0\t1\t1\tnoarch");
        }
        parser.accept("RPM_STATUS\tCOLLECTED");
        parser.accept(os("os_release", "A".repeat(16_385)));
        parser.accept("OSINFO\tkernel_release\tCOLLECTED\t!!!");
        parser.accept(os("secret_file", "excluded"));
        var inventory = parser.build(new ArrayList<>());
        assertThat(inventory.rpm().packages()).hasSize(20_000);
        assertThat(inventory.rpm().status()).isEqualTo(Status.TRUNCATED);
        assertThat(inventory.os()).noneMatch(d -> d.key().equals("secret_file"));
        assertThat(inventory.os()).anyMatch(d -> d.key().equals("os_release") && d.status() == Status.TRUNCATED && d.value().length() == 16_384);
        assertThat(inventory.os()).anyMatch(d -> d.key().equals("kernel_release") && d.status() == Status.FAILED);
    }

    @Test
    void readsOldSnapshotsAsNotCollectedAndRetainsNewInventoryAfterAssessment() {
        var json = JsonMapper.builder().build();
        ObjectNode tree = (ObjectNode) json.readTree(json.writeValueAsString(ReportFixtures.sample()));
        tree.remove("systemInventory");
        var old = json.readValue(json.writeValueAsString(tree), Observation.class);
        assertThat(old.systemInventory().rpm().status()).isEqualTo(Status.NOT_COLLECTED);
        var parser = new SystemInventoryParser();
        parser.accept("RPM\trpm\t0\t4.18\t1.el9\tx86_64");
        parser.accept("RPM_STATUS\tCOLLECTED");
        var inventory = parser.build(new ArrayList<>());
        var fresh = new Observation(old.version(), old.collectedAt(), old.host(), old.processes(), old.javaProcesses(),
                old.containers(), old.issues(), old.facts(), null, inventory);
        var restored = json.readValue(json.writeValueAsString(fresh.assessed(null)), Observation.class);
        assertThat(restored.systemInventory()).isEqualTo(inventory);
    }

    @Test
    void sharedShellRunsWithoutJavaPythonOrRpmAndCollectsRealKernel() throws Exception {
        prepareTools();
        var inventory = shell("");
        assertThat(inventory.os()).anyMatch(d -> d.key().equals("kernel_release") && d.status() == Status.COLLECTED
                && d.value().equals(System.getProperty("os.version")));
        assertThat(inventory.rpm().status()).isEqualTo(Status.UNAVAILABLE);
    }

    @Test
    void sharedShellUsesReadOnlyRpmQueryAndPreservesFailureAndLimits() throws Exception {
        prepareTools();
        var inventory = shell("[ \"$1\" = -qa ] && [ \"$2\" = --qf ] || exit 9\nprintf 'RPM\\tpkg\\t(none)\\t1.2\\t3.el9\\tx86_64\\n'\n");
        assertThat(inventory.rpm().status()).isEqualTo(Status.COLLECTED);
        assertThat(inventory.rpm().packages().getFirst().version()).isEqualTo("1.2");
        assertThat(shell("exit 1\n").rpm().status()).isEqualTo(Status.FAILED);
        assertThat(shell("exit 124\n").rpm().status()).isEqualTo(Status.TIMEOUT);
        assertThat(shell("awk 'BEGIN { for(i=0;i<20001;i++) print \"RPM\\tpkg-\" i \"\\t0\\t1\\t1\\tnoarch\" }'\n").rpm().status()).isEqualTo(Status.TRUNCATED);
        var byteLimited = shell("awk 'BEGIN { name=sprintf(\"%0500d\",1); for(i=0;i<15000;i++) print \"RPM\\t\" name \"\\t0\\t1\\t1\\tnoarch\" }'\n");
        assertThat(byteLimited.rpm().status()).isEqualTo(Status.TRUNCATED);
        assertThat(byteLimited.rpm().packages().size()).isBetween(1, 14_999);
        Files.delete(temporary.resolve("timeout"));
        assertThat(shell("exit 0\n").rpm().message()).contains("timeout");
    }

    private void prepareTools() throws Exception {
        for (String tool : List.of("uname", "head", "base64", "tr", "awk", "timeout")) {
            Files.createSymbolicLink(temporary.resolve(tool), Path.of("/usr/bin", tool));
        }
    }

    private SystemInventory shell(String rpmBody) throws Exception {
        if (!rpmBody.isEmpty()) {
            Path rpm = temporary.resolve("rpm");
            Files.writeString(rpm, "#!/bin/sh\n" + rpmBody);
            assertThat(rpm.toFile().setExecutable(true, true)).isTrue();
        }
        String script = SystemInventoryCollector.script().replace("export PATH=/usr/sbin:/usr/bin:/sbin:/bin", "export PATH='" + temporary + "'");
        var result = new CommandRunner(new ObserverProperties()).run(List.of("/bin/sh", "-s"), script, Duration.ofSeconds(30), 5 * 1024 * 1024);
        assertThat(result.success()).isTrue();
        var parser = new SystemInventoryParser();
        result.output().lines().forEach(parser::accept);
        return parser.build(new ArrayList<>());
    }

    private String os(String key, String value) {
        return "OSINFO\t" + key + "\tCOLLECTED\t" + Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }
}
