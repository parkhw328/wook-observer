package com.wook.observer.report;

import com.wook.observer.ReportFixtures;
import com.wook.observer.model.Assessment;
import com.wook.observer.model.Observation;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.io.ByteArrayInputStream;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class OperatorReportTest {
    @Test
    void groupsLegacyActionsAndLibrariesWithoutLosingRawEvidenceOrIncludingUnknowns() throws Exception {
        var base = ReportFixtures.sample();
        var process = base.processes().getFirst();
        var second = new Observation.ProcessInfo(124,process.parentPid(),process.name(),process.executable(),process.user(),
                process.startedAt(),process.cpuMillis(),process.residentBytes(),process.threads(),process.category(),process.classificationEvidence(),process.containerId());
        var library = new Observation.Library("group:library","1.0","/lib/library.jar","Maven pom.properties","unknown","unknown");
        String firstAsset = Assessment.libraryAsset(process.pid(),library), secondAsset = Assessment.libraryAsset(124,library);
        var finding = new Assessment.Finding("CVE","CVE-TEST",firstAsset,"발견","HIGH","example","range 0 to 2","https://example.test/advisory");
        var assessment = new Assessment(3,"2026-11-11T00:00:00Z",Instant.now(),"original scope",List.of(finding,finding,
                new Assessment.Finding("CVE","CVE-TEST",secondAsset,"발견","HIGH","example","range 0 to 2","https://example.test/advisory"),
                new Assessment.Finding("CCE","CCE-TEST","host","점검 불가","UNKNOWN","wrong OS","적용 플랫폼 미일치/미확인: rhel:9",""),
                new Assessment.Finding("CVE","CVE-SAFE",firstAsset,"미발견","LOW","not affected","range outside","")));
        var report = new Observation(base.version(),base.collectedAt(),base.host(),List.of(process,second),
                List.of(new Observation.JavaInfo(process.pid(),"",Map.of(),List.of(library)),new Observation.JavaInfo(124,"",Map.of(),List.of(library))),
                List.of(),List.of(base.issues().getFirst(),base.issues().getFirst()),Map.of(),assessment);
        var operator = OperatorReport.from(report);
        assertThat(operator.actions()).singleElement().satisfies(a -> {
            assertThat(a.id()).isEqualTo("CVE-TEST");
            assertThat(a.locations()).hasSize(2);
            assertThat(a.evidence()).hasSize(1);
        });
        assertThat(operator.libraries()).singleElement().satisfies(l -> {
            assertThat(l.locations()).hasSize(2);
            assertThat(l.advisories()).containsExactly("CVE-TEST");
        });
        assertThat(operator.issues()).hasSize(1);
        assertThat(operator.coverage()).anyMatch(c -> c.status().equals("미판정"));
        var writer = new ReportWriter(new MarkdownReports(),new ExcelReports());
        var docs = writer.documents(report);
        assertThat(docs.get("actions.md")).doesNotContain("CCE-TEST","CVE-SAFE").contains("PID 123","PID 124");
        assertThat(docs.get("summary.md")).contains("발견 1건").doesNotContain("services/cache/");
        assertThat(docs.get("report.json")).contains("CCE-TEST","CVE-SAFE");
        var json = JsonMapper.builder().build();
        assertThat(json.readValue(docs.get("report.json"),Observation.class).assessment().findings()).hasSize(5);
        try (var workbook = new XSSFWorkbook(new ByteArrayInputStream(writer.xlsx(report)))) {
            assertThat(workbook.getSheet("취약점 상세").getLastRowNum()).isEqualTo(3);
            assertThat(workbook.isSheetHidden(workbook.getSheetIndex("JSON 원문"))).isTrue();
            assertThat(workbook.isSheetHidden(workbook.getSheetIndex("캐시_프로세스"))).isTrue();
            assertThat(workbook.isSheetHidden(workbook.getSheetIndex("애플리케이션_프로세스"))).isFalse();
            assertThat(ExcelReportsTest.restoredSources(workbook)).isEqualTo(docs);
        }
        var oldJson = json.readTree(docs.get("report.json"));
        ((tools.jackson.databind.node.ObjectNode)oldJson.path("assessment")).remove("coverage");
        for (var row : oldJson.path("assessment").path("findings")) {
            var object = (tools.jackson.databind.node.ObjectNode)row;
            for (String key : List.of("packageName","version","recommendation","aliases")) { object.remove(key); }
        }
        var restored = json.treeToValue(oldJson,Observation.class);
        assertThat(OperatorReport.from(restored).actions()).hasSize(1);
        assertThat(restored.assessment().catalogRevision()).isEqualTo(3);
    }
}
