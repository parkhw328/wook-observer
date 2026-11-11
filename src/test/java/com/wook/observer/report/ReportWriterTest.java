package com.wook.observer.report;

import com.wook.observer.ReportFixtures;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.zip.ZipInputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ReportWriterTest {
    @TempDir Path temporary;
    private final ReportWriter writer = new ReportWriter(new MarkdownReports(), new ExcelReports());

    @Test
    void separatesAllThreeSpecificationTypesByServiceCategoryAndIncludesEvidence() {
        var files = writer.documents(ReportFixtures.sample());
        assertThat(files).hasSize(25).containsKeys("summary.md", "report.json", "services/application/processes.md",
                "services/application/open-source.md", "services/application/containers.md", "services/database/processes.md");
        assertThat(files.get("services/application/open-source.md")).contains("library", "1.2.3", "manifest");
        assertThat(files.get("services/database/open-source.md")).doesNotContain("1.2.3");
        assertThat(files.get("summary.md")).contains("권한 부족", "services/application/processes.md");
        assertThat(files.get("services/application/containers.md")).contains("&lt;script&gt;&#124;").doesNotContain("<script>");
        assertThat(files.get("report.json")).contains("2026-11-11T00:00:00Z");
    }

    @Test
    void writesPortableZipWithRelativePaths() throws Exception {
        var names = new ArrayList<String>();
        try (var zip = new ZipInputStream(new ByteArrayInputStream(writer.zip(ReportFixtures.sample())), StandardCharsets.UTF_8)) {
            for (var entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) {
                names.add(entry.getName());
                if (entry.getName().equals("report.xlsx")) {
                    try (var workbook = new XSSFWorkbook(new ByteArrayInputStream(zip.readAllBytes()))) {
                        assertThat(workbook.getSheet("요약")).isNotNull();
                        assertThat(workbook.getSheet("JSON 원문")).isNotNull();
                    }
                }
            }
        }
        assertThat(names).hasSize(26).contains("summary.md", "services/application/open-source.md", "report.json", "report.xlsx");
        assertThat(names).noneMatch(name -> name.startsWith("/") || name.contains(".."));
    }

    @Test
    void refusesToOverwriteExistingReports() throws Exception {
        Path destination = temporary.resolve("scan");
        writer.writeDirectory(ReportFixtures.sample(), destination);
        try (var workbook = new XSSFWorkbook(destination.resolve("report.xlsx").toFile())) {
            assertThat(workbook.getNumberOfSheets()).isEqualTo(32);
            assertThat(ExcelReportsTest.restoredSources(workbook)).isEqualTo(writer.documents(ReportFixtures.sample()));
        }
        String original = Files.readString(destination.resolve("summary.md"));
        assertThatThrownBy(() -> writer.writeDirectory(ReportFixtures.sample(), destination))
                .isInstanceOf(FileAlreadyExistsException.class);
        assertThat(Files.readString(destination.resolve("summary.md"))).isEqualTo(original);
    }
}
