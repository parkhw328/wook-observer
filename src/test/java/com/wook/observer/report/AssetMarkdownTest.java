package com.wook.observer.report;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;

class AssetMarkdownTest {
    @Test
    void includesLegacyOsUpgradeActionAndConditionalElsWithoutAddingVulnerabilityFindings() {
        var summary = new com.wook.observer.platform.ServiceDiscovery.Summary(
                new com.wook.observer.model.Observation.Host("legacy", "Linux", "3.10", "x86_64", 2, 1024, "fixture", ""),
                "Red Hat Enterprise Linux Server 7.9 (Maipo)", "UNAVAILABLE", "ss unavailable", 1, 0, 1, null);
        var asset = Map.<String, Object>of("name", "기존 RHEL 자산", "inventory", summary, "finding_count", 0);
        String report = AssetMarkdown.write(List.of(asset), a -> List.of());
        assertThat(report).contains("OS 업그레이드 필요", "정규 유지보수 종료", "2024-06-30", "2029-05-31", "계약", "미확인",
                "https://access.redhat.com/articles/7005471", "| 최근 완료 점검의 발견·의심 | 0 |");
    }

    @Test
    void includesUnscannedAndArchivedRunbooksAndSafelyPreservesMultilineCommands() {
        var asset = Map.<String, Object>of("name", "<script>server</script>", "host", "offline", "location", "IDC | 2층\n랙 3", "notes", "![tracking](https://example.com)");
        String command = "echo ```quoted```\nprintf '한글|<x>'\n" + "긴 명령".repeat(1000);
        var service = Map.<String, Object>of("name", "수동 서비스", "archived", true, "start_command", command, "observation_status", "MANUAL");
        String report = AssetMarkdown.write(List.of(asset), a -> List.of(service));
        assertThat(report).contains("등록 자산 1대", "서버 위치", "IDC &#124; 2층<br>랙 3", "보관", "미수집", "수동 서비스", "````text\n" + command + "\n````");
        assertThat(report).doesNotContain("<script>", "![tracking]");
    }
    @Test
    void emptyInventoryStillProducesReadableSingleReport() {
        assertThat(AssetMarkdown.write(List.of(), a -> List.of())).contains("등록 자산 0대", "등록한 자산이 없습니다.");
    }
}
