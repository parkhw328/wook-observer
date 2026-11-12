package com.wook.observer.platform;

import com.wook.observer.ReportFixtures;
import com.wook.observer.model.Assessment;
import com.wook.observer.model.Observation;
import com.wook.observer.model.SystemInventory;
import com.wook.observer.model.SystemInventory.*;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;

class RpmAssessorTest {
    private final JsonMapper json = JsonMapper.builder().build();

    @Test
    void matchesOfficialVendorAdvisoriesAndTheirBackportedFixBoundaries() throws Exception {
        var records = json.readTree(getClass().getResourceAsStream("/catalog/rpm-advisories.json")).valueStream().toList();
        for (String os : List.of("rhel", "rocky", "almalinux")) {
            var vulnerable = assess(os, "9.0", rpm(os, "openssl", "1", "3.0.1", "42.el9_0"), records);
            assertThat(vulnerable.findings()).singleElement().satisfies(f -> {
                assertThat(f.kind()).isEqualTo("RPM");
                assertThat(f.status()).isEqualTo("발견");
                assertThat(f.id()).endsWith("-2022:7288");
                assertThat(f.recommendation()).contains("1:3.0.1-43.el9_0");
                assertThat(f.relatedIds()).contains("CVE-2022-3786");
                assertThat(f.aliases()).doesNotContain("CVE-2022-3786");
                assertThat(f.evidence()).contains("x86_64", os + ":9.0");
            });
            var fixed = assess(os, "9.0", rpm(os, "openssl", "1", "3.0.1", "43.el9_0"), records);
            assertThat(fixed.findings()).isEmpty();
            assertThat(fixed.coverage()).anyMatch(c -> c.kind().equals("RPM") && c.status().equals("미발견"));
            assertThat(assess(os, "9.0", rpm(os, "openssl", "2", "1.0", "1.el9"), records).findings()).isEmpty();
        }
    }

    @Test
    void supportsAllThreeDistributionMajorsWithoutCrossMatching() {
        for (String os : List.of("rhel", "rocky", "almalinux")) {
            for (String major : List.of("8", "9", "10")) {
                String ecosystem = ecosystem(os, major);
                JsonNode record = advisory(os, ecosystem, "openssl", "1:3.0-2.el" + major);
                assertThat(assess(os, major + ".0", rpm(os, "openssl", "1", "3.0", "1.el" + major), List.of(record)).findings()).hasSize(1);
                assertThat(assess(os, "7.9", rpm(os, "openssl", "1", "3.0", "1.el7"), List.of(record)).findings()).isEmpty();
                for (String other : List.of("rhel", "rocky", "almalinux")) {
                    if (!other.equals(os)) { assertThat(assess(other, major + ".0", rpm(other, "openssl", "1", "3.0", "1.el" + major), List.of(record)).findings()).isEmpty(); }
                }
            }
        }
    }

    @Test
    void keepsSourceRpmMatchesAsCandidatesAndDoesNotInferSafeBinaries() throws Exception {
        var records = json.readTree(getClass().getResourceAsStream("/catalog/rpm-advisories.json")).valueStream().toList();
        var installed = new RpmPackage("openssl-libs", "1", "3.0.1", "42.el9_0", "x86_64",
                "openssl-3.0.1-42.el9_0.src.rpm", "", "Rocky Enterprise Software Foundation");
        assertThat(assess("rocky", "9.0", installed, records).findings()).singleElement().satisfies(f -> {
            assertThat(f.status()).isEqualTo("의심");
            assertThat(f.evidence()).contains("SOURCERPM=openssl-3.0.1-42.el9_0.src.rpm");
        });
        var fixed = new RpmPackage("openssl-libs", "1", "3.0.1", "43.el9_0", "x86_64",
                "openssl-3.0.1-43.el9_0.src.rpm", "", "Rocky Enterprise Software Foundation");
        var result = assess("rocky", "9.0", fixed, records);
        assertThat(result.findings()).isEmpty();
        assertThat(result.coverage()).anyMatch(c -> c.kind().equals("RPM") && c.status().equals("미판정"));
    }

    @Test
    void requiresMatchingArchitectureVendorModuleAndCompleteIdentity() {
        var record = advisory("rocky", "Rocky Linux:9", "openssl", "1:3.0-2.el9");
        var affected = (ObjectNode) record.path("affected").get(0);
        ((ObjectNode) affected.path("package")).put("purl", "pkg:rpm/rocky-linux/openssl?arch=aarch64&epoch=1&distro=rocky-linux-9");
        var base = rpm("rocky", "openssl", "1", "3.0", "1.el9");
        var result = assess("rocky", "9.0", base, List.of(record));
        assertThat(result.findings()).isEmpty();
        assertThat(result.coverage()).anyMatch(c -> c.status().equals("비대상"));
        ((ObjectNode) affected.path("package")).remove("purl");
        for (var pkg : List.of(new RpmPackage("openssl", "1", "3.0", "1.el9", "x86_64"),
                new RpmPackage("openssl", "1", "3.0", "1.el9", "x86_64", "", "", "Other Vendor"),
                new RpmPackage("openssl", "1", "3.0", "1.el9", "x86_64", "", "openssl:3:1:abcd", base.vendor()),
                new RpmPackage("openssl", "", "3.0", "1.el9", "x86_64", "", "", base.vendor()))) {
            var rejected = assess("rocky", "9.0", pkg, List.of(record));
            assertThat(rejected.findings()).isEmpty();
            assertThat(rejected.coverage()).anyMatch(c -> c.status().equals("미판정"));
        }
    }

    @Test
    void withdrawnAndNonSecurityUpdatesNeverBecomeVulnerabilities() {
        var record = (ObjectNode) advisory("almalinux", "AlmaLinux:9", "openssl", "1:3.0-2.el9");
        record.put("withdrawn", "2026-11-01T00:00:00Z");
        var installed = rpm("almalinux", "openssl", "1", "3.0", "1.el9");
        assertThat(assess("almalinux", "9.0", installed, List.of(record)).findings()).isEmpty();
        record.remove("withdrawn"); record.put("id", "ALBA-2026:12345");
        assertThat(assess("almalinux", "9.0", installed, List.of(record)).findings()).isEmpty();
    }

    @Test
    void rhelRequiresRepositoryCpeEvidenceAndExplicitEusReleaseLock() {
        var installed = rpm("rhel", "openssl", "1", "3.0", "1.el9_4");
        var standard = advisory("rhel", "Red Hat:enterprise_linux:9::baseos", "openssl", "1:3.0-5.el9_4");
        var eus = advisory("rhel", "Red Hat:rhel_eus:9.4::baseos", "openssl", "1:3.0-2.el9_4");
        var unknown = assess("rhel", "9.4", List.of(installed), List.of(standard), List.of(), "", Status.NOT_COLLECTED);
        assertThat(unknown.findings()).isEmpty();
        assertThat(unknown.coverage()).anyMatch(c -> c.reason().contains("CPE"));
        var repos = List.of("rhel-9-for-x86_64-baseos-eus-rpms");
        assertThat(assess("rhel", "9.4", List.of(installed), List.of(eus), repos, "", Status.COLLECTED).findings()).isEmpty();
        var match = assess("rhel", "9.4", List.of(installed), List.of(eus, standard), repos, "9.4", Status.COLLECTED);
        assertThat(match.findings()).singleElement().satisfies(f -> {
            assertThat(f.evidence()).contains("rhel_eus:9.4");
            assertThat(f.recommendation()).contains("1:3.0-2.el9_4").doesNotContain("1:3.0-5.el9_4");
        });
    }

    @Test
    void respectsInclusiveAndExclusiveBoundariesAndRejectsIncompleteRanges() {
        var installed = rpm("rocky", "openssl", "1", "3.0", "2.el9");
        var platform = new RpmAssessor.Platform("rocky", "9");
        for (String key : List.of("fixed", "limit", "last_affected")) {
            var node = json.readTree("{\"ranges\":[{\"type\":\"ECOSYSTEM\",\"events\":[{\"introduced\":\"0\"},{\"" + key + "\":\"1:3.0-2.el9\"}]}]}");
            var result = RpmAssessor.evaluate(installed, node, platform);
            assertThat(result.match()).isEqualTo(key.equals("last_affected") ? RpmAssessor.Match.YES : RpmAssessor.Match.NO);
            assertThat(result.fixed()).isEmpty();
        }
        assertThat(RpmAssessor.evaluate(installed, json.readTree("{\"versions\":[\"1:3.0-1.el9\"]}"), platform).match()).isEqualTo(RpmAssessor.Match.NO);
        assertThat(RpmAssessor.evaluate(installed, json.readTree("{\"versions\":[\"1:3.0-2.el9\"]}"), platform).match()).isEqualTo(RpmAssessor.Match.YES);
        for (String raw : List.of("{\"versions\":[\"1:3.0-2.el8\"]}",
                "{\"ranges\":[{\"type\":\"GIT\",\"events\":[{\"introduced\":\"0\"}]}]}",
                "{\"ranges\":[{\"type\":\"ECOSYSTEM\",\"events\":[{\"introduced\":\"0\"},{\"fixed\":\"1:3.0-3.el9\"},{\"fixed\":\"1:3.0-4.el9\"}]}]}")) {
            assertThat(RpmAssessor.evaluate(installed, json.readTree(raw), platform).match()).isEqualTo(RpmAssessor.Match.UNKNOWN);
        }
    }

    private String ecosystem(String os, String major) {
        return switch (os) { case "rhel" -> "Red Hat:enterprise_linux:" + major + "::baseos"; case "rocky" -> "Rocky Linux:" + major; default -> "AlmaLinux:" + major; };
    }

    private JsonNode advisory(String os, String ecosystem, String name, String fixed) {
        String prefix = switch (os) { case "rhel" -> "RHSA"; case "rocky" -> "RLSA"; default -> "ALSA"; };
        return json.readTree("{\"id\":\"" + prefix + "-2026:12345\",\"affected\":[{\"package\":{\"name\":\"" + name
                + "\",\"ecosystem\":\"" + ecosystem + "\"},\"ranges\":[{\"type\":\"ECOSYSTEM\",\"events\":[{\"introduced\":\"0\"},{\"fixed\":\"" + fixed + "\"}]}]}]}");
    }

    private RpmPackage rpm(String os, String name, String epoch, String version, String release) {
        String vendor = switch (os) { case "rhel" -> "Red Hat, Inc."; case "rocky" -> "Rocky Enterprise Software Foundation"; default -> "AlmaLinux"; };
        return new RpmPackage(name, epoch, version, release, "x86_64", name + "-" + version + "-" + release + ".src.rpm", "", vendor);
    }

    private Assessment assess(String os, String version, RpmPackage installed, List<JsonNode> records) {
        return assess(os, version, List.of(installed), records, List.of("rhel-" + version.split("\\.")[0] + "-for-x86_64-baseos-rpms"), "", Status.COLLECTED);
    }

    private Assessment assess(String os, String version, List<RpmPackage> installed, List<JsonNode> records,
                              List<String> repositories, String release, Status repositoryStatus) {
        var base = ReportFixtures.sample();
        var inventory = new SystemInventory(List.of(), new RpmInventory(Status.COLLECTED, "fixture", installed, repositories, release, repositoryStatus));
        var report = new Observation("1.5.0", base.collectedAt(), base.host(), List.of(), List.of(), List.of(), List.of(),
                Map.of("host:os.id", os, "host:os.version", version), null, inventory);
        return new Assessor(null).assess(report, new Catalog.Snapshot(3, "", records)).assessment();
    }
}
