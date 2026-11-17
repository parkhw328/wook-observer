package com.wook.observer.platform;

import com.wook.observer.model.Observation;
import com.wook.observer.model.SystemInventory;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.time.LocalDate;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;

class OsLifecycleTest {
    private final LocalDate today = LocalDate.of(2026, 11, 17);
    @Test
    void recordedElsAppliesOnlyToRhel79ThroughTheContractEndDate() {
        var identity = OsLifecycle.identify(report("ID=rhel\nVERSION_ID=7.9", ""));
        var contract = Map.<String, Object>of("els_contract", true, "els_valid_until", today.toString());
        var valid = OsLifecycle.withContract(identity, contract, today);
        assertThat(valid.status()).isEqualTo("ELS_RECORDED");
        assertThat(valid.upgradeRequired()).isFalse();
        assertThat(valid.note()).contains("운영자", today.toString(), "자동 계약 조회 결과가 아니며");
        var expired = OsLifecycle.withContract(identity, contract, today.plusDays(1));
        assertThat(expired.status()).isEqualTo("ELS_EXPIRED");
        assertThat(expired.upgradeRequired()).isTrue();
        assertThat(OsLifecycle.withContract(identity, Map.of("els_contract", false, "els_valid_until", "2029-05-31"), today).upgradeRequired()).isTrue();
        for (String version : List.of("7", "7.8")) {
            var support = OsLifecycle.withContract(OsLifecycle.identify(report("ID=rhel\nVERSION_ID=" + version, "")), contract, today);
            assertThat(support.status()).isEqualTo("ELS_VERSION_CHECK");
            assertThat(support.upgradeRequired()).isTrue();
        }
        assertThat(OsLifecycle.withContract(OsLifecycle.identify(report("ID=centos\nVERSION_ID=7.9", "")), contract, today).status()).isEqualTo("END_OF_LIFE");
        for (String date : List.of("", "bad", "2024-06-30", "2029-06-01"))
            assertThat(OsLifecycle.withContract(identity, Map.of("els_contract", true, "els_valid_until", date), today).status()).isEqualTo("MAINTENANCE_ENDED");
        assertThat(OsLifecycle.withContract(null, contract, today).status()).isEqualTo("UNKNOWN");
    }

    @Test
    void refinesCoarseRhelVersionOnlyWithMatchingReleaseEvidence() {
        assertThat(OsLifecycle.identify(report("ID=rhel\nVERSION_ID=7", "Red Hat Enterprise Linux Server release 7.9 (Maipo)")).version()).isEqualTo("7.9");
        assertThat(OsLifecycle.identify(report("ID=rhel\nVERSION_ID=7", "CentOS Linux release 7.9.2009 (Core)")).version()).isEqualTo("7");
        assertThat(OsLifecycle.identify(report("ID=rhel\nVERSION_ID=7.8", "Red Hat Enterprise Linux Server release 7.9 (Maipo)")).version()).isEqualTo("7.8");
    }
    static Observation report(String osRelease, String redhatRelease) {
        return new Observation("1.10.1", Instant.parse("2026-01-01T00:00:00Z"),
                new Observation.Host("legacy", "Linux", "5.14.0.el9", "x86_64", 2, 1L << 30, "fixture", ""),
                List.of(), List.of(), List.of(), List.of(), Map.of(), null,
                new SystemInventory(List.of(
                        new SystemInventory.OsDetail("os_release", "fixture", SystemInventory.Status.COLLECTED, osRelease),
                        new SystemInventory.OsDetail("redhat_release", "fixture", SystemInventory.Status.COLLECTED, redhatRelease)), null, null));
    }
    @Test
    void distinguishesCentosEolAndRhelMaintenanceEndWithoutAssumingAnElsContract() {
        for (String version : List.of("7", "7.0", "7.9", "7.9.2009")) {
            var centos = OsLifecycle.evaluate(OsLifecycle.identify(report("ID=centos\nVERSION_ID=\"" + version + "\"", "")), today);
            var rhel = OsLifecycle.evaluate(OsLifecycle.identify(report("ID=rhel\nVERSION_ID=" + version, "")), today);
            assertThat(centos.status()).isEqualTo("END_OF_LIFE");
            assertThat(rhel.status()).isEqualTo("MAINTENANCE_ENDED");
            assertThat(centos.upgradeRequired()).isTrue(); assertThat(rhel.upgradeRequired()).isTrue();
            assertThat(centos.endedOn()).isEqualTo("2024-06-30");
            assertThat(centos.extendedUntil()).isEmpty();
            assertThat(rhel.note()).contains("별도 계약", "7.9", "미확인");
            assertThat(rhel.extendedUntil()).isEqualTo("2029-05-31");
        }
    }
    @Test
    void doesNotInferDistributionFromKernelDerivativesOrUnregisteredVersions() {
        for (String os : List.of("", "ID=ol\nID_LIKE=rhel\nVERSION_ID=7.9", "ID=rocky\nVERSION_ID=7", "ID=rhel\nVERSION_ID=9",
                "ID=centos\nVERSION_ID=8", "ID=centos\nVERSION_ID=70", "ID=centos\nVERSION_ID=7x", "ID=centos\nNAME=CentOS Stream\nVERSION_ID=7",
                "ID=rhel\nID=centos\nVERSION_ID=7")) {
            var support = OsLifecycle.evaluate(OsLifecycle.identify(report(os, "")), today);
            assertThat(support.status()).as(os).isEqualTo("UNKNOWN");
            assertThat(support.upgradeRequired()).isFalse();
            assertThat(support.note()).contains("지원 중이라는 뜻은 아닙니다");
        }
    }
    @Test
    void usesExplicitLegacyReleaseEvidenceWithoutExecutingItsContents() {
        var centos = OsLifecycle.identify(report("", "CentOS Linux release 7.9.2009 (Core)"));
        var rhel = OsLifecycle.identify(report("", "Red Hat Enterprise Linux Server release 7.9 (Maipo)"));
        assertThat(centos.id()).isEqualTo("centos"); assertThat(centos.version()).isEqualTo("7.9.2009");
        assertThat(rhel.id()).isEqualTo("rhel"); assertThat(rhel.source()).isEqualTo("/etc/redhat-release");
        assertThat(OsLifecycle.identify(report("ID=ol\nVERSION_ID=7", "Red Hat Enterprise Linux Server release 7.9 (Maipo)")).id()).isEqualTo("ol");
        assertThat(OsLifecycle.identify(report("ID=$(touch /tmp/should-never-run)\nVERSION_ID=7", "")).id()).contains("$(touch");
        assertThat(OsLifecycle.fromName("Linux 3.10.0.el7", "old").id()).isEmpty();
        assertThat(OsLifecycle.fromName("CentOS Stream 7", "old").id()).isEmpty();
    }
    @Test
    void honorsTheEndDateAndKeepsUnknownContractsSeparateFromLifecycle() {
        var identity = OsLifecycle.fromName("CentOS Linux 7 (Core)", "fixture");
        assertThat(OsLifecycle.evaluate(identity, LocalDate.of(2024, 6, 30)).upgradeRequired()).isFalse();
        assertThat(OsLifecycle.evaluate(identity, LocalDate.of(2024, 7, 1)).upgradeRequired()).isTrue();
    }
    @Test
    void readsOldSummaryJsonAndAppliesPolicyWithoutRewritingIt() {
        var mapper = JsonMapper.builder().build();
        ObjectNode tree = (ObjectNode) mapper.readTree(mapper.writeValueAsString(ServiceDiscovery.summary(report("ID=centos\nVERSION_ID=7\nPRETTY_NAME=\"CentOS Linux 7 (Core)\"", ""))));
        tree.remove("osIdentity");
        String saved = mapper.writeValueAsString(tree);
        var summary = mapper.readValue(saved, ServiceDiscovery.Summary.class);
        assertThat(summary.osIdentity()).isNull();
        assertThat(OsLifecycle.forSummary(summary).upgradeRequired()).isTrue();
        assertThat(mapper.writeValueAsString(tree)).isEqualTo(saved);
    }
}
