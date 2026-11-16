package com.wook.observer.report;

import com.wook.observer.platform.ServiceDiscovery;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.LongStream;

import static org.assertj.core.api.Assertions.assertThat;

class AssetWorkbookTest {
    @Test
    void exportsSavedManualFieldsObservationEvidenceAndArchivedServicesAsLiteralText() throws Exception {
        var asset = new HashMap<String, Object>(Map.of("id", "asset-1", "name", "운영 서버", "host", "192.0.2.10", "asset_code", "000123", "private_key", "must-never-be-exported"));
        var observed = new ServiceDiscovery.Candidate("key", "PROCESS", "nginx", "웹", List.of("TCP 0.0.0.0:443", "TCP [::]:443"),
                List.of(1001L, 1002L, 1003L, 1004L), "/usr/sbin/nginx", "web", "", "", "");
        var service = new HashMap<String, Object>(Map.of("id", "svc-1", "observed", observed, "observation_status", "NOT_OBSERVED", "archived", true,
                "name", "=HYPERLINK(\"https://example.invalid\",\"literal\")", "start_command", "cd /APP/example\n./start.sh", "bindings", "운영 8443", "status_command", "@status", "notes", "한글 메모"));
        try (var book = write(List.of(asset), List.of(service))) {
            assertThat(book.getNumberOfSheets()).isEqualTo(4);
            var inventory = book.getSheet("자산 목록");
            assertThat(inventory.getRow(1).getCell(2).getStringCellValue()).isEqualTo("000123");
            assertThat(inventory.getRow(1).getCell(8).getStringCellValue()).isEqualTo("미수집");
            var grid = book.getSheet("서비스 운영대장");
            assertThat(grid.getRow(1).getCell(3).getCellType()).isEqualTo(CellType.STRING);
            assertThat(grid.getRow(1).getCell(3).getStringCellValue()).startsWith("=HYPERLINK");
            assertThat(grid.getRow(1).getCell(8).getStringCellValue()).isEqualTo("web");
            assertThat(grid.getRow(1).getCell(12).getStringCellValue()).contains("0.0.0.0:443", "[::]:443");
            assertThat(grid.getRow(1).getCell(13).getStringCellValue()).isEqualTo("운영 8443");
            assertThat(grid.getRow(1).getCell(14).getStringCellValue()).isEqualTo("cd /APP/example\n./start.sh");
            assertThat(grid.getRow(1).getCell(19).getStringCellValue()).isEqualTo("최근 수집 미확인");
            assertThat(grid.getRow(1).getCell(20).getStringCellValue()).isEqualTo("보관");
            assertThat(grid.getRow(1).getCell(22).getStringCellValue()).isEqualTo("1001\n1002\n1003\n1004");
            assertThat(grid.getPaneInformation().getVerticalSplitPosition()).isEqualTo((short) 3);
            assertThat(grid.getCTWorksheet().getAutoFilter().getRef()).isEqualTo("A1:AD2");
            assertThat(values(book.getSheet("포트·PID 상세"), "PID")).containsExactly("1001", "1002", "1003", "1004");
            for (var sheet : book) for (var row : sheet) for (var cell : row) {
                assertThat(cell.getCellType()).isNotEqualTo(CellType.FORMULA);
                assertThat(cell.toString()).doesNotContain("must-never-be-exported");
            }
        }
    }

    @Test
    void preservesLongUnicodePortEvidenceAndAllPidsBeyondTheCellLimit() throws Exception {
        String longPort = "Docker " + "가😀".repeat(12_000);
        var pids = LongStream.range(1_000_000, 1_005_000).boxed().toList();
        var observed = new ServiceDiscovery.Candidate("key", "CONTAINER", "batch", "애플리케이션", List.of(longPort), pids, "", "", "example:1", "container", "running");
        try (var book = write(List.of(Map.of("id", "a", "name", "서버")), List.of(Map.of("id", "s", "observed", observed)))) {
            var grid = book.getSheet("서비스 운영대장");
            assertThat(grid.getRow(1).getCell(12).getStringCellValue()).contains("전체 값: 포트·PID 상세");
            assertThat(grid.getRow(1).getCell(22).getStringCellValue()).contains("전체 값: 포트·PID 상세");
            assertThat(String.join("", values(book.getSheet("포트·PID 상세"), "자동 관측 포트"))).isEqualTo(longPort);
            assertThat(values(book.getSheet("포트·PID 상세"), "PID")).containsExactlyElementsOf(pids.stream().map(String::valueOf).toList());
        }
    }

    @Test
    void emptyInventoryStillProducesUsableSheetsAndHeaders() throws Exception {
        try (var book = write(List.of(), List.of())) {
            assertThat(book.getNumberOfSheets()).isEqualTo(4);
            assertThat(book.getSheet("자산 목록").getLastRowNum()).isZero();
            assertThat(book.getSheet("서비스 운영대장").getRow(0).getCell(14).getStringCellValue()).isEqualTo("시작 방법");
        }
    }

    private XSSFWorkbook write(List<Map<String, Object>> assets, List<Map<String, Object>> services) throws Exception {
        var bytes = new ByteArrayOutputStream();
        new AssetWorkbook().write(assets, ignored -> services, bytes);
        return new XSSFWorkbook(new ByteArrayInputStream(bytes.toByteArray()));
    }
    private List<String> values(Sheet sheet, String kind) {
        List<String> values = new ArrayList<>();
        for (var row : sheet) if (row.getRowNum() > 0 && row.getCell(3).getStringCellValue().equals(kind)) values.add(row.getCell(5).getStringCellValue());
        return values;
    }
}
