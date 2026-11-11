package com.wook.observer.report;

import com.wook.observer.ReportFixtures;
import com.wook.observer.model.Observation;
import com.wook.observer.model.ServiceCategory;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

class ExcelReportsTest {
    private final ExcelReports excel = new ExcelReports();
    private final ReportWriter reports = new ReportWriter(new MarkdownReports(), excel);

    @Test
    void exportsOsAndRpmEvidenceAndUnsupportedVulnerabilityStatusWithoutFormulaExecution() throws Exception {
        var base = ReportFixtures.sample();
        var parser = new com.wook.observer.collector.SystemInventoryParser();
        parser.accept("OSINFO\tkernel_release\tCOLLECTED\tNS4xNA==");
        parser.accept("RPM\t=hostile()\t2\t1.2\t3.el9\tx86_64");
        parser.accept("RPM_STATUS\tCOLLECTED");
        var report = new Observation(base.version(), base.collectedAt(), base.host(), base.processes(), base.javaProcesses(),
                base.containers(), base.issues(), base.facts(), null, parser.build(new java.util.ArrayList<>()));
        var docs = reports.documents(report);
        assertThat(docs.get("host/os.md")).contains("uname -r", "5.14");
        assertThat(docs.get("host/rpm-packages.md")).contains("1.2", "3.el9", "x86&#95;64", "RPM CVE 대조는 미지원");
        try (var workbook = workbook(report, docs)) {
            assertThat(summaryValue(workbook, "RPM 수집 항목 수")).isEqualTo(1);
            var row = workbook.getSheet("RPM 패키지").getRow(3);
            assertThat(row.getCell(0).getCellType()).isEqualTo(CellType.STRING);
            assertThat(row.getCell(0).getStringCellValue()).isEqualTo("=hostile()");
            assertThat(row.getCell(5).getStringCellValue()).isEqualTo("=hostile()-2:1.2-3.el9.x86_64");
            assertThat(row.getCell(6)).isNull();
            assertThat(workbook.getSheet("RPM 패키지").getRow(1).getCell(0).getStringCellValue()).contains("CVE 대조 미지원");
            assertThat(restoredSources(workbook)).isEqualTo(docs);
        }
    }

    @Test
    void includesAllSpecificationsAndExactOriginalDocumentsInOneWorkbook() throws Exception {
        var report = ReportFixtures.sample();
        var originals = reports.documents(report);
        try (var workbook = workbook(report, originals)) {
            assertThat(workbook.getNumberOfSheets()).isEqualTo(33);
            assertThat(workbook.getStylesSource().getCTStylesheet().getCellStyles().getCellStyleArray(0).getName()).isEqualTo("Normal");
            assertThat(restoredSources(workbook)).isEqualTo(originals);
            for (ServiceCategory category : ServiceCategory.values()) {
                assertThat(workbook.getSheet(category.label() + "_프로세스")).isNotNull();
                assertThat(workbook.getSheet(category.label() + "_오픈소스")).isNotNull();
                assertThat(workbook.getSheet(category.label() + "_컨테이너")).isNotNull();
            }
            assertThat(summaryValue(workbook, "프로세스 수")).isEqualTo(1);
            assertThat(summaryValue(workbook, "고유 라이브러리 수(직군·버전별)")).isEqualTo(1);
            assertThat(summaryValue(workbook, "수집 오류 수")).isEqualTo(1);
            assertThat(workbook.getSheet("애플리케이션_오픈소스").getRow(3).getCell(1).getStringCellValue()).isEqualTo("1.2.3");
            assertThat(workbook.getSheet("컨테이너 연결").getLastRowNum()).isEqualTo(5);
            assertThat(workbook.getSheet("수집 오류").getRow(3).getCell(2).getStringCellValue()).isEqualTo("권한 부족");
            assertThat(workbook.getSheet("JVM 속성").getRow(3).getCell(5).getStringCellValue()).isEqualTo("21.0.13");
            for (var row : workbook.getSheet("원문 목록")) {
                if (row.getRowNum() < 3) { continue; }
                byte[] original = originals.get(row.getCell(0).getStringCellValue()).getBytes(StandardCharsets.UTF_8);
                assertThat(row.getCell(1).getNumericCellValue()).isEqualTo(original.length);
                assertThat(row.getCell(2).getStringCellValue())
                        .isEqualTo(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(original)));
            }
        }
    }

    @Test
    void providesFrozenHeadersFiltersAndWorkingInternalLinks() throws Exception {
        var report = ReportFixtures.sample();
        try (var workbook = workbook(report, reports.documents(report))) {
            for (Sheet sheet : workbook) {
                assertThat(sheet.getPaneInformation().isFreezePane()).isTrue();
                assertThat(sheet.getPaneInformation().getHorizontalSplitPosition()).isEqualTo((short) 3);
                assertThat(workbook.getSheet(sheet.getSheetName()).getCTWorksheet().isSetAutoFilter()).isTrue();
                for (var row : sheet) {
                    for (var cell : row) {
                        if (cell.getHyperlink() == null) { continue; }
                        String address = cell.getHyperlink().getAddress();
                        int separator = address.lastIndexOf("'!A");
                        String target = address.substring(1, separator).replace("''", "'");
                        int targetRow = Integer.parseInt(address.substring(separator + 3)) - 1;
                        assertThat(workbook.getSheet(target)).isNotNull();
                        assertThat((Object) workbook.getSheet(target).getRow(targetRow)).isNotNull();
                    }
                }
            }
        }
    }

    @Test
    void preservesLongUnicodeOriginalsWithoutSplittingSurrogatePairsOrLosingLineEndings() throws Exception {
        String text = "가".repeat(15_999) + "🚀\r\n" + IntStream.range(0, 8000)
                .mapToObj(index -> "원문-" + index + "-🚀<&>\r\n").collect(Collectors.joining());
        var originals = new LinkedHashMap<String, String>();
        originals.put("summary.md", text);
        originals.put("report.json", "{\"content\":\"value\"}\n");
        try (var workbook = workbook(ReportFixtures.sample(), originals)) {
            assertThat(restoredSources(workbook)).isEqualTo(originals);
            assertThat(workbook.getSheet("Markdown 원문").getLastRowNum()).isGreaterThan(3);
            for (String chunk : ExcelReports.chunks(text)) {
                assertThat(chunk.length()).isLessThanOrEqualTo(16_000);
                assertThat(Character.isLowSurrogate(chunk.charAt(0))).isFalse();
                assertThat(Character.isHighSurrogate(chunk.charAt(chunk.length() - 1))).isFalse();
            }
        }
    }

    @Test
    void storesFormulaLikeMetadataAsStringsAndKeepsLargeIntegersExact() throws Exception {
        var base = ReportFixtures.sample();
        var process = new Observation.ProcessInfo(Long.MAX_VALUE, 1, "=HYPERLINK(\"https://invalid.example\",\"run\")",
                "/bin/java", "+SUM(1,2)", null, null, 0L, null, ServiceCategory.APPLICATION, "@formula-like", "abc123");
        var report = new Observation(base.version(), base.collectedAt(), base.host(), List.of(process), List.of(), List.of(), List.of());
        try (var workbook = workbook(report, reports.documents(report))) {
            var row = workbook.getSheet("애플리케이션_프로세스").getRow(3);
            assertThat(row.getCell(0).getCellType()).isEqualTo(CellType.STRING);
            assertThat(row.getCell(0).getStringCellValue()).isEqualTo(Long.toString(Long.MAX_VALUE));
            assertThat(row.getCell(2).getCellType()).isEqualTo(CellType.STRING);
            assertThat(row.getCell(2).getStringCellValue()).isEqualTo(process.name());
            assertThat(row.getCell(6).getCellType()).isEqualTo(CellType.BLANK);
            assertThat(row.getCell(7).getNumericCellValue()).isZero();
            for (Sheet sheet : workbook) {
                for (var dataRow : sheet) {
                    for (var cell : dataRow) { assertThat(cell.getCellType()).isNotEqualTo(CellType.FORMULA); }
                }
            }
        }
    }

    @Test
    void retainsLongJvmValuesInJsonWhenTheirTablePreviewExceedsTheCellLimit() throws Exception {
        var base = ReportFixtures.sample();
        String value = IntStream.range(0, 8000).mapToObj(index -> "/lib/module-" + index + ".jar").collect(Collectors.joining(":"));
        var java = new Observation.JavaInfo(123, "완료", Map.of("java.class.path", value), List.of());
        var report = new Observation(base.version(), base.collectedAt(), base.host(), base.processes(), List.of(java), base.containers(), base.issues());
        var originals = reports.documents(report);
        try (var workbook = workbook(report, originals)) {
            var preview = workbook.getSheet("JVM 속성").getRow(3).getCell(5).getStringCellValue();
            assertThat(preview.length()).isLessThanOrEqualTo(32767);
            assertThat(preview).endsWith("[전체 내용: JSON 원문]");
            assertThat(restoredSources(workbook).get("report.json")).isEqualTo(originals.get("report.json")).contains(value);
        }
    }

    @Test
    void preservesEmptySnapshotsAndMissingJvmPropertiesWithoutInventingData() throws Exception {
        var base = ReportFixtures.sample();
        var empty = new Observation(base.version(), base.collectedAt(), base.host(), List.of(), List.of(), List.of(), List.of());
        try (var workbook = workbook(empty, reports.documents(empty))) {
            assertThat(summaryValue(workbook, "프로세스 수")).isZero();
            assertThat(workbook.getSheet("애플리케이션_프로세스").getLastRowNum()).isEqualTo(2);
            assertThat(workbook.getSheet("수집 오류").getLastRowNum()).isEqualTo(2);
            assertThat(restoredSources(workbook)).isEqualTo(reports.documents(empty));
        }
        var java = new Observation.JavaInfo(999, "권한 부족", Map.of(), List.of());
        var partial = new Observation(base.version(), base.collectedAt(), base.host(), List.of(), List.of(java), List.of(), List.of());
        try (var workbook = workbook(partial, reports.documents(partial))) {
            assertThat(workbook.getSheet("JVM 속성").getRow(3).getCell(0).getStringCellValue()).isEqualTo("기타");
            assertThat(workbook.getSheet("JVM 속성").getRow(3).getCell(3).getStringCellValue()).isEqualTo("권한 부족");
        }
    }

    private XSSFWorkbook workbook(Observation report, Map<String, String> documents) throws Exception {
        var bytes = new ByteArrayOutputStream();
        excel.write(report, documents, bytes);
        return new XSSFWorkbook(new ByteArrayInputStream(bytes.toByteArray()));
    }

    static Map<String, String> restoredSources(XSSFWorkbook workbook) {
        Map<String, StringBuilder> sources = new LinkedHashMap<>();
        for (String name : List.of("Markdown 원문", "JSON 원문")) {
            for (var row : workbook.getSheet(name)) {
                if (row.getRowNum() < 3) { continue; }
                sources.computeIfAbsent(row.getCell(0).getStringCellValue(), ignored -> new StringBuilder())
                        .append(row.getCell(2).getStringCellValue());
            }
        }
        Map<String, String> result = new LinkedHashMap<>();
        sources.forEach((name, contents) -> result.put(name, contents.toString()));
        return result;
    }

    private static double summaryValue(XSSFWorkbook workbook, String key) {
        for (var row : workbook.getSheet("요약")) {
            if (row.getRowNum() >= 3 && row.getCell(0).getStringCellValue().equals(key)) {
                return row.getCell(1).getNumericCellValue();
            }
        }
        throw new AssertionError("Missing summary key: " + key);
    }
}
