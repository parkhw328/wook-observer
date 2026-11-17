package com.wook.observer.report;

import com.wook.observer.platform.ServiceDiscovery;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.streaming.SXSSFWorkbook;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.OutputStream;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/** Current saved runbooks, separate from immutable inspection reports. */
@Component
public class AssetWorkbook {
    private static final Map<String, String> LABELS = Map.ofEntries(
            Map.entry("UNSPECIFIED", "미지정"), Map.entry("PRODUCTION", "운영"), Map.entry("STAGING", "검증"),
            Map.entry("DEVELOPMENT", "개발"), Map.entry("TEST", "테스트"), Map.entry("ACTIVE", "운영"),
            Map.entry("MAINTENANCE", "유지보수"), Map.entry("RETIRED", "폐기·사용 종료"),
            Map.entry("OTHER", "미분류"), Map.entry("MIDDLEWARE", "미들웨어"), Map.entry("APPLICATION", "애플리케이션"),
            Map.entry("COMMON", "공통·보안·백업"), Map.entry("MANUAL", "수동 등록"), Map.entry("AUTO", "자동 후보"),
            Map.entry("OBSERVED", "최근 수집 확인"), Map.entry("NOT_OBSERVED", "최근 수집 미확인"),
            Map.entry("COMPLETED", "완료"), Map.entry("FAILED", "실패"), Map.entry("RUNNING", "진행 중"), Map.entry("QUEUED", "대기"));

    public void write(List<Map<String, Object>> assets, Function<Map<String, Object>, List<Map<String, Object>>> services,
                      OutputStream output) throws IOException {
        try (var book = new SXSSFWorkbook(100)) {
            book.setCompressTempFiles(true);
            var normal = book.getXSSFWorkbook().getStylesSource().getCTStylesheet().addNewCellStyles();
            var style = normal.addNewCellStyle();
            style.setName("Normal"); style.setXfId(0); style.setBuiltinId(0); normal.setCount(1);
            book.getXSSFWorkbook().getProperties().getCoreProperties().setCreator("Observer");
            book.getXSSFWorkbook().getProperties().getCoreProperties().setTitle("자산·서비스 운영대장");
            var header = book.createCellStyle();
            var font = book.createFont(); font.setBold(true); font.setColor(IndexedColors.WHITE.getIndex()); font.setFontHeightInPoints((short) 11);
            header.setFont(font); header.setFillForegroundColor(IndexedColors.GREY_80_PERCENT.getIndex()); header.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            var body = book.createCellStyle(); body.setWrapText(true); body.setVerticalAlignment(VerticalAlignment.TOP);
            var bodyFont = book.createFont(); bodyFont.setFontHeightInPoints((short) 11); body.setFont(bodyFont);
            Table guide = new Table(book, header, body, "내보내기 안내", 1, "항목", "내용");
            Table inventory = new Table(book, header, body, "자산 목록", 3,
                    "서버명", "접속 주소", "자산번호", "환경", "담당자", "서버 위치", "용도", "운영 상태", "수집 호스트", "OS", "아키텍처", "CPU", "메모리(GiB)",
                    "최근 수집 시각(UTC)", "최근 점검 상태", "최근 점검 메시지", "관리 서비스 수", "절차 보완 수", "발견·의심 수", "자산 메모", "수정자", "수정 시각(UTC)", "자산 ID");
            Table runbook = new Table(book, header, body, "서비스 운영대장", 3,
                    "서버명", "접속 주소", "자산번호", "서비스명", "구분", "SW", "버전", "담당파트", "실행계정", "APP / 설치위치", "DATA", "LOG",
                    "자동 관측 포트", "운영 포트 / 보완", "시작 방법", "종료 방법", "상태 확인 방법", "연계 대상 / 방향", "메모", "관측 상태", "보관 여부",
                    "최근 관측 시각(UTC)", "PID", "관측 실행 파일", "관측 이미지", "관측 컨테이너 ID", "수정자", "수정 시각(UTC)", "서비스 ID", "자산 ID");
            Table evidence = new Table(book, header, body, "포트·PID 상세", 3,
                    "서버명", "접속 주소", "서비스명", "종류", "순번", "원문 값", "관측 상태", "관측 시각(UTC)", "보관 여부", "서비스 ID", "자산 ID", "조각 번호");
            long count = 0, archived = 0;
            for (var asset : assets) {
                var observed = (ServiceDiscovery.Summary) asset.get("inventory");
                inventory.add(text(asset, "name"), text(asset, "host"), text(asset, "asset_code"), label(asset, "environment", "미지정"), text(asset, "owner"),
                        text(asset, "location"), text(asset, "purpose"), label(asset, "lifecycle", "운영"), observed == null ? "미수집" : observed.host().name(),
                        observed == null ? "미수집" : observed.distribution(), observed == null ? "" : observed.host().architecture(),
                        observed == null || observed.host().processors() <= 0 ? "미수집" : observed.host().processors(),
                        observed == null || observed.host().physicalMemoryBytes() <= 0 ? "미수집" : observed.host().physicalMemoryBytes() / (double) (1L << 30),
                        text(asset, "observed_at"), label(asset, "latest_status", "점검 전"), text(asset, "latest_message"), asset.get("service_count"), asset.get("undocumented_count"),
                        asset.get("finding_count"), text(asset, "notes"), text(asset, "updated_by"), text(asset, "updated_at"), text(asset, "id"));
                for (var service : services.apply(asset)) {
                    count++;
                    boolean isArchived = Boolean.TRUE.equals(service.get("archived"));
                    if (isArchived) archived++;
                    var candidate = (ServiceDiscovery.Candidate) service.get("observed");
                    String name = text(service, "name");
                    if (name.isEmpty() && candidate != null) name = candidate.name();
                    String user = text(service, "run_user");
                    if (user.isEmpty() && candidate != null) user = candidate.runUser();
                    runbook.add(text(asset, "name"), text(asset, "host"), text(asset, "asset_code"), name, label(service, "service_type", "미분류"),
                            text(service, "software"), text(service, "software_version"), text(service, "owner"), user, text(service, "install_path"), text(service, "data_path"), text(service, "log_path"),
                            candidate == null ? "미확인" : preview(candidate.bindings()), text(service, "bindings"), text(service, "start_command"), text(service, "stop_command"), text(service, "status_command"),
                            text(service, "dependencies"), text(service, "notes"), label(service, "observation_status", ""), isArchived ? "보관" : "관리 중", text(service, "last_seen_at"),
                            candidate == null ? "" : preview(candidate.pids()), candidate == null ? "" : candidate.executable(), candidate == null ? "" : candidate.image(),
                            candidate == null ? "" : candidate.containerId(), text(service, "updated_by"), text(service, "updated_at"), text(service, "id"), text(asset, "id"));
                    if (candidate != null) {
                        evidence(evidence, asset, service, name, "자동 관측 포트", candidate.bindings());
                        evidence(evidence, asset, service, name, "PID", candidate.pids());
                        evidence(evidence, asset, service, name, "관측 실행 파일", List.of(candidate.executable()));
                        evidence(evidence, asset, service, name, "관측 이미지", List.of(candidate.image()));
                    }
                }
            }
            guide.add("생성 시각(UTC)", Instant.now().toString());
            guide.add("자산 수", assets.size()); guide.add("서비스 수(보관 포함)", count); guide.add("보관 서비스 수", archived);
            guide.add("범위", "전체 자산 또는 선택한 자산의 저장된 전체 서비스(보관 포함). 화면 검색·필터와 저장 전 입력은 반영하지 않습니다.");
            guide.add("관측과 수기", "자동 관측 포트·PID는 최근 저장 근거이며 실시간 상태가 아닙니다. 운영 포트·경로·시작/종료/상태 확인 방법은 운영자가 작성한 정보입니다. 명령은 실행하지 않습니다.");
            guide.add("전체 값", "화면에서 축약한 주소·Docker 매핑·PID는 포트·PID 상세 시트에 항목별로 보존합니다. 긴 원문 값은 순번·조각 번호 순으로 구분자 없이 이어 붙이면 복원됩니다. 빈 자동 관측 포트는 미확인입니다.");
            guide.add("과거 보고서", "다운로드 시점의 저장된 운영대장입니다. 과거 점검 보고서·판정을 변경하거나 서버에 다시 접속하지 않습니다. 시각은 UTC입니다.");
            for (var table : List.of(guide, inventory, runbook, evidence)) table.finish();
            guide.sheet.setColumnWidth(1, 100 * 256);
            evidence.sheet.setColumnWidth(5, 70 * 256);
            for (int column : new int[]{9, 10, 11, 12, 13, 14, 15, 16, 17, 18}) runbook.sheet.setColumnWidth(column, 32 * 256);
            book.write(output);
        }
    }

    private static void evidence(Table table, Map<String, Object> asset, Map<String, Object> service, String name, String kind, List<?> values) {
        for (int index = 0; index < values.size(); index++) {
            var parts = ExcelReports.chunks(String.valueOf(values.get(index)));
            for (int part = 0; part < parts.size(); part++) {
                table.add(text(asset, "name"), text(asset, "host"), name, kind, index + 1, parts.get(part), label(service, "observation_status", ""),
                        text(service, "last_seen_at"), Boolean.TRUE.equals(service.get("archived")) ? "보관" : "관리 중", text(service, "id"), text(asset, "id"), part + 1);
            }
        }
    }

    private static String preview(List<?> values) { return values.isEmpty() ? "미확인" : clip(String.join("\n", values.stream().map(String::valueOf).toList())); }
    private static String text(Map<String, Object> row, String key) { return row.get(key) == null ? "" : String.valueOf(row.get(key)); }
    private static String label(Map<String, Object> row, String key, String empty) { String value = text(row, key); return value.isEmpty() ? empty : LABELS.getOrDefault(value, value); }
    private static String clip(String value) {
        if (value.length() <= 32_000) return value;
        int end = Character.isHighSurrogate(value.charAt(31_999)) ? 31_999 : 32_000;
        return value.substring(0, end) + "\n… 전체 값: 포트·PID 상세 시트";
    }

    private static final class Table {
        final Sheet sheet;
        final CellStyle body;
        final int columns;
        int next = 1;
        Table(Workbook book, CellStyle header, CellStyle body, String name, int freeze, String... labels) {
            this.body = body; columns = labels.length; sheet = book.createSheet(name);
            Row row = sheet.createRow(0); row.setHeightInPoints(26);
            for (int i = 0; i < labels.length; i++) {
                var cell = row.createCell(i); cell.setCellValue(labels[i]); cell.setCellStyle(header);
                sheet.setColumnWidth(i, 24 * 256);
            }
            sheet.createFreezePane(freeze, 1); sheet.setRepeatingRows(new CellRangeAddress(0, 0, -1, -1));
            sheet.getPrintSetup().setLandscape(true); sheet.setZoom(85);
        }
        void add(Object... values) {
            Row row = sheet.createRow(next++);
            for (int i = 0; i < values.length; i++) {
                var cell = row.createCell(i); cell.setCellStyle(body);
                if (values[i] instanceof Number number) cell.setCellValue(number.doubleValue());
                // Explicit STRING cells keep formula-like commands and imported text inert.
                else cell.setCellValue(values[i] == null ? "" : clip(String.valueOf(values[i])));
            }
        }
        void finish() { sheet.setAutoFilter(new CellRangeAddress(0, Math.max(0, next - 1), 0, columns - 1)); }
    }
}
