package com.wook.observer.report;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;

class AssetMarkdownTest {
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
