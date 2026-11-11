package com.wook.observer.report;

import com.wook.observer.model.Observation;
import com.wook.observer.model.Assessment;
import com.wook.observer.model.ServiceCategory;
import org.apache.poi.common.usermodel.HyperlinkType;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.VerticalAlignment;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.streaming.SXSSFWorkbook;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class ExcelReports {
    private static final int SOURCE_CHUNK_LENGTH = 16_000;
    private static final int DISPLAY_LENGTH = 32_000;
    private static final String SOURCE_NOTE = "같은 문서의 조각 번호 순으로 내용을 구분자 없이 이어 붙이면 원문이 복원됩니다.";
    private static final String LONG_VALUE_NOTE = "매우 긴 표의 값은 축약 표시하며 전체 값은 JSON 원문에 보존합니다.";

    public void write(Observation report, Map<String, String> documents, OutputStream output) throws IOException {
        try (SXSSFWorkbook workbook = new SXSSFWorkbook(100)) {
            workbook.setCompressTempFiles(true);
            var namedStyles = workbook.getXSSFWorkbook().getStylesSource().getCTStylesheet().addNewCellStyles();
            var normal = namedStyles.addNewCellStyle();
            normal.setName("Normal");
            normal.setXfId(0);
            normal.setBuiltinId(0);
            namedStyles.setCount(1);
            workbook.getXSSFWorkbook().getProperties().getCoreProperties().setCreator("Wook Observer");
            workbook.getXSSFWorkbook().getProperties().getCoreProperties().setTitle("서버 점검 통합 보고서");
            Styles styles = new Styles(workbook);
            List<Table> tables = new ArrayList<>();
            Map<Long, Observation.ProcessInfo> byPid = new LinkedHashMap<>();
            report.processes().forEach(process -> byPid.put(process.pid(), process));

            Table summary = table(workbook, styles, tables, "요약", "동일 스냅샷의 명세와 원문을 담은 통합 보고서입니다.", "항목", "값");
            summary.sheet.getPrintSetup().setFitHeight((short) 1);
            summary.add("보고서 버전", report.version());
            summary.add("수집 시작 시각(UTC)", report.collectedAt().toString());
            summary.add("호스트", report.host().name());
            summary.add("관측 범위", report.host().scope());
            summary.add("프로세스 수", report.processes().size());
            summary.add("Java 프로세스 수", report.javaProcesses().size());
            summary.add("컨테이너 수", report.containers().size());
            summary.add("컨테이너 연결 프로세스 수", report.processes().stream().filter(process -> !process.containerId().isBlank()).count());
            summary.add("라이브러리 항목 수(프로세스별 중복 포함)", report.javaProcesses().stream().mapToLong(java -> java.libraries().size()).sum());
            summary.add("수집 오류 수", report.issues().size());
            summary.add("RPM 수집 상태", report.systemInventory().rpm().status().label());
            summary.add("RPM 수집 항목 수", report.systemInventory().rpm().packages().size());
            if (report.assessment() != null) {
                summary.add("취약점 데이터 버전", report.assessment().catalogRevision());
                summary.add("취약점 데이터 반영 시각", report.assessment().catalogUpdatedAt());
                summary.add("취약점 판정 범위", report.assessment().scope());
                Table findings = table(workbook, styles, tables, "취약점 상세", "발견은 기준과의 일치이며 악용 가능성을 확정하지 않습니다. 점검 불가를 안전으로 해석하지 마세요.",
                        "종류", "ID", "대상", "취약점발견", "심각도", "제목", "근거", "참조");
                report.assessment().findings().forEach(f -> findings.add(f.kind(), f.id(), f.asset(), f.status(), f.severity(), f.title(), f.evidence(), f.reference()));
                Row link = summary.add("취약점 상세", "시트 열기");
                summary.link(link.getCell(1), findings.name(), 0);
            }
            summary.add("원문 문서 수", documents.size());
            summary.add("해석 주의", "라이브러리 발견은 실제 로딩이나 오픈소스 여부·라이선스 확인을 의미하지 않습니다. Java 수에는 수집기 자체가 포함될 수 있습니다.");
            summary.add("관측 한계", "RPM 외 패키지 상세, RPM CVE 대조, 컨테이너별 RPM DB, 동적 클래스 로더, systemd, 호스트 리스닝 포트, Kubernetes 계층은 미지원입니다. SSH와 로컬 수집의 도구·권한 차이는 수집 오류를 확인하세요.");
            summary.add("분류 기준", "실행 파일·이미지 이름 휴리스틱 및 사용자 설정. 기타 직군은 자동 분류 미확정 항목을 포함합니다.");
            summary.add("숫자와 긴 값", "컨테이너 ID·15자리 초과 정수는 텍스트로 보존합니다. " + LONG_VALUE_NOTE);

            Table host = table(workbook, styles, tables, "호스트", "수집기에서 접근 가능한 OS·자원 범위입니다.", "항목", "값");
            host.add("호스트명", report.host().name());
            host.add("OS", report.host().os());
            host.add("OS/커널 버전", report.host().osVersion());
            host.add("아키텍처", report.host().architecture());
            host.add("CPU 수", report.host().processors());
            host.add("물리 메모리(bytes, -1은 미확인)", report.host().physicalMemoryBytes());
            host.add("관측 범위", report.host().scope());
            host.add("Docker 엔진 버전", report.host().dockerVersion());

            Table os = table(workbook, styles, tables, "OS·커널", "배포판과 실행 커널을 구분합니다. 컨테이너의 배포판 파일과 호스트 공유 커널이 함께 보일 수 있습니다. 항목당 16 KiB, 끝 개행 생략.",
                    "항목", "근거", "수집 상태", "값");
            report.systemInventory().os().forEach(d -> os.add(d.key(), d.source(), d.status().label(), d.value()));
            if (report.systemInventory().os().isEmpty()) { os.add("OS 상세", "", "미수집", "이전 스냅샷은 재점검 필요"); }
            var rpm = report.systemInventory().rpm();
            Table packages = table(workbook, styles, tables, "RPM 패키지", rpm.status().label() + ": " + rpm.message()
                    + " 근거: rpm -qa --qf (NAME, EPOCH, VERSION, RELEASE, ARCH). Epoch 미지정=0. 20초·20,000건·4 MiB 제한. 호스트 파일시스템 범위.",
                    "이름", "Epoch", "버전", "Release", "아키텍처", "NEVRA", "취약점발견");
            rpm.packages().forEach(p -> packages.add(p.name(), p.epoch(), p.version(), p.release(), p.architecture(), p.nevra(), "점검 불가 (RPM 대조 미지원)"));

            Table categories = table(workbook, styles, tables, "직군별 현황", "명세 열의 링크를 누르면 해당 직군의 상세 시트로 이동합니다.",
                    "직군", "코드", "프로세스 수", "JVM 수", "라이브러리 항목 수", "컨테이너 수", "프로세스 명세", "오픈소스 명세", "컨테이너 명세");
            Table jvms = table(workbook, styles, tables, "JVM 속성", "허용된 JVM 속성만 수록합니다. " + LONG_VALUE_NOTE,
                    "직군", "PID", "컨테이너 ID", "조회 상태", "속성", "값");
            Table connections = table(workbook, styles, tables, "컨테이너 연결", "PID·포트·네트워크·마운트 목록의 각 항목을 별도 행으로 보존합니다.",
                    "직군", "컨테이너 ID", "컨테이너명", "종류", "순번", "값");
            Table issues = table(workbook, styles, tables, "수집 오류", report.issues().isEmpty()
                    ? "보고된 수집 오류 없음. 요약에 기재된 미지원 범위는 계속 적용됩니다."
                    : "일부 수집 실패·권한 부족·누락·제한을 확인하세요.", "계층", "대상", "내용");
            report.issues().forEach(issue -> issues.add(issue.layer(), issue.target(), issue.message()));

            Map<ServiceCategory, Table> processSheets = new EnumMap<>(ServiceCategory.class);
            Map<ServiceCategory, Table> librarySheets = new EnumMap<>(ServiceCategory.class);
            Map<ServiceCategory, Table> containerSheets = new EnumMap<>(ServiceCategory.class);
            for (ServiceCategory category : ServiceCategory.values()) {
                Table processes = table(workbook, styles, tables, category.label() + "_프로세스", "CPU는 시작 후 누적 시간이며 시간은 UTC입니다. " + LONG_VALUE_NOTE,
                        "PID", "PPID", "프로세스명", "실행 파일", "소유자", "시작 시각(UTC)", "누적 CPU(ms)", "RSS(bytes)", "스레드 수", "컨테이너 ID", "분류 근거", "취약점발견");
                Table libraries = table(workbook, styles, tables, category.label() + "_오픈소스", "Java 라이브러리 후보입니다. 실제 로딩·라이선스·오픈소스 여부는 별도 확인이 필요합니다.",
                        "PID", "이름", "버전", "경로", "버전 근거", "라이선스", "로딩 상태", "취약점발견");
                Table containers = table(workbook, styles, tables, category.label() + "_컨테이너", "태그와 실제 이미지 ID를 구분합니다. 연결 상세에서 전체 목록을 확인하세요.",
                        "컨테이너 ID", "이름", "이미지", "이미지 ID", "상태", "호스트 init PID", "프로세스 수", "포트 수", "네트워크 수", "마운트 수", "분류 근거", "연결 상세", "취약점발견");
                processSheets.put(category, processes);
                librarySheets.put(category, libraries);
                containerSheets.put(category, containers);
                long javaCount = report.javaProcesses().stream().filter(java -> categoryOf(byPid, java.pid()) == category).count();
                long libraryCount = report.javaProcesses().stream().filter(java -> categoryOf(byPid, java.pid()) == category)
                        .mapToLong(java -> java.libraries().size()).sum();
                Row row = categories.add(category.label(), category.name(),
                        report.processes().stream().filter(process -> process.category() == category).count(), javaCount, libraryCount,
                        report.containers().stream().filter(container -> container.category() == category).count(), "열기", "열기", "열기");
                categories.link(row.getCell(6), processes.name(), 0);
                categories.link(row.getCell(7), libraries.name(), 0);
                categories.link(row.getCell(8), containers.name(), 0);
            }

            for (var process : report.processes()) {
                processSheets.get(process.category()).add(process.pid(), process.parentPid(), process.name(), process.executable(),
                        process.user(), process.startedAt(), process.cpuMillis(), process.residentBytes(), process.threads(),
                        process.containerId(), process.classificationEvidence(), report.assessment() == null ? "미실시" : report.assessment().processStatus(process.pid()));
            }
            for (var java : report.javaProcesses()) {
                ServiceCategory category = categoryOf(byPid, java.pid());
                String container = byPid.containsKey(java.pid()) ? byPid.get(java.pid()).containerId() : "";
                if (java.properties().isEmpty()) {
                    jvms.add(category.label(), java.pid(), container, java.status(), null, null);
                } else {
                    java.properties().entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry ->
                            jvms.add(category.label(), java.pid(), container, java.status(), entry.getKey(), entry.getValue()));
                }
                java.libraries().forEach(library -> librarySheets.get(category).add(java.pid(), library.name(), library.version(),
                        library.path(), library.evidence(), library.license(), library.loadingStatus(),
                        report.assessment() == null ? "미실시" : report.assessment().status(Assessment.libraryAsset(java.pid(), library))));
            }
            for (var container : report.containers()) {
                int firstDetailRow = connections.nextRow;
                connectionRows(connections, container, "PID", container.processIds());
                connectionRows(connections, container, "포트", container.ports());
                connectionRows(connections, container, "네트워크", container.networks());
                connectionRows(connections, container, "마운트", container.mounts());
                Table table = containerSheets.get(container.category());
                Row row = table.add(container.id(), container.name(), container.image(), container.imageId(), container.state(),
                        container.initPid(), container.processIds().size(), container.ports().size(), container.networks().size(),
                        container.mounts().size(), container.classificationEvidence(), firstDetailRow == connections.nextRow ? "수집된 연결 없음" : "열기",
                        report.assessment() == null ? "미실시" : report.assessment().status("container:" + container.id()));
                if (firstDetailRow != connections.nextRow) { table.link(row.getCell(11), connections.name(), firstDetailRow); }
            }

            Table sourceIndex = table(workbook, styles, tables, "원문 목록", "원문은 기존 Markdown·JSON과 동일합니다. SHA-256은 UTF-8 원문 기준입니다.",
                    "문서", "UTF-8 bytes", "SHA-256", "원문 시트", "첫 조각 행", "조각 수");
            Table markdown = table(workbook, styles, tables, "Markdown 원문", SOURCE_NOTE, "문서", "조각", "원문");
            Table json = table(workbook, styles, tables, "JSON 원문", SOURCE_NOTE, "문서", "조각", "원문");
            for (var entry : documents.entrySet()) {
                Table target = entry.getKey().endsWith(".json") ? json : markdown;
                int firstRow = target.nextRow;
                List<String> chunks = chunks(entry.getValue());
                for (int index = 0; index < chunks.size(); index++) {
                    target.add(entry.getKey(), index + 1, chunks.get(index));
                }
                byte[] original = entry.getValue().getBytes(StandardCharsets.UTF_8);
                Row row = sourceIndex.add(entry.getKey(), original.length, sha256(original), target.name(), firstRow + 1, chunks.size());
                sourceIndex.link(row.getCell(3), target.name(), firstRow);
            }
            for (String name : List.of("호스트", "직군별 현황", "JVM 속성", "컨테이너 연결", "수집 오류", "원문 목록", "Markdown 원문", "JSON 원문")) {
                Row row = summary.add(name, "시트 열기");
                summary.link(row.getCell(1), name, 0);
            }
            summary.add("원문 복원", SOURCE_NOTE);
            tables.forEach(Table::finish);
            workbook.setActiveSheet(0);
            workbook.write(output);
        }
    }

    private static Table table(Workbook workbook, Styles styles, List<Table> tables, String name, String note, String... headers) {
        Table table = new Table(workbook, styles, name, note, headers);
        tables.add(table);
        return table;
    }

    private static ServiceCategory categoryOf(Map<Long, Observation.ProcessInfo> processes, long pid) {
        return processes.containsKey(pid) ? processes.get(pid).category() : ServiceCategory.OTHER;
    }

    private static void connectionRows(Table table, Observation.ContainerInfo container, String kind, List<?> values) {
        for (int index = 0; index < values.size(); index++) {
            table.add(container.category().label(), container.id(), container.name(), kind, index + 1, values.get(index));
        }
    }

    static List<String> chunks(String value) {
        List<String> result = new ArrayList<>();
        for (int start = 0; start < value.length();) {
            int end = Math.min(start + SOURCE_CHUNK_LENGTH, value.length());
            int newlines = 0;
            for (int index = start; index < end; index++) {
                if (value.charAt(index) == '\n' && ++newlines == 100) { end = index + 1; break; }
            }
            if (end < value.length() && Character.isHighSurrogate(value.charAt(end - 1))
                    && Character.isLowSurrogate(value.charAt(end))) { end--; }
            result.add(value.substring(start, end));
            start = end;
        }
        return result.isEmpty() ? List.of("") : result;
    }

    private static String sha256(byte[] value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value)); }
        catch (NoSuchAlgorithmException exception) { throw new IllegalStateException("SHA-256 unavailable", exception); }
    }

    private static class Table {
        private final Workbook workbook;
        private final Sheet sheet;
        private final Styles styles;
        private final int columns;
        private int nextRow = 3;

        Table(Workbook workbook, Styles styles, String name, String note, String[] headers) {
            this.workbook = workbook;
            this.styles = styles;
            this.columns = headers.length;
            sheet = workbook.createSheet(name);
            sheet.setDefaultRowHeightInPoints(30);
            sheet.setDisplayGridlines(false);
            sheet.setZoom(85);
            heading(0, name, styles.title, 32);
            heading(1, note, styles.subtitle, 42);
            Row header = sheet.createRow(2);
            header.setHeightInPoints(32);
            for (int index = 0; index < headers.length; index++) {
                Cell cell = header.createCell(index);
                cell.setCellValue(headers[index]);
                cell.setCellStyle(styles.header);
                sheet.setColumnWidth(index, width(headers[index]) * 256);
            }
            sheet.createFreezePane(1, 3);
            sheet.getPrintSetup().setLandscape(columns > 3);
            sheet.getPrintSetup().setFitWidth((short) 1);
            sheet.getPrintSetup().setFitHeight((short) 0);
            sheet.setFitToPage(true);
            sheet.setRepeatingRows(new CellRangeAddress(0, 2, -1, -1));
        }

        String name() { return sheet.getSheetName(); }

        Row add(Object... values) {
            if (nextRow >= workbook.getSpreadsheetVersion().getMaxRows()) {
                throw new IllegalStateException("XLSX 행 제한 초과: " + name());
            }
            Row row = sheet.createRow(nextRow++);
            row.setHeightInPoints(name().endsWith("원문") ? 90 : 36);
            for (int index = 0; index < values.length; index++) {
                Cell cell = row.createCell(index);
                cell.setCellStyle(styles.text);
                Object value = values[index];
                if (value == null) { continue; }
                if (value instanceof Number number && number.longValue() >= -999_999_999_999_999L
                        && number.longValue() <= 999_999_999_999_999L) {
                    cell.setCellValue(number.doubleValue());
                    cell.setCellStyle(styles.number);
                } else {
                    String text = value.toString();
                    if (text.length() > DISPLAY_LENGTH) {
                        int end = Character.isHighSurrogate(text.charAt(DISPLAY_LENGTH - 1)) ? DISPLAY_LENGTH - 1 : DISPLAY_LENGTH;
                        text = text.substring(0, end) + "… [전체 내용: JSON 원문]";
                    }
                    // Explicit string cells prevent formula interpretation of collected metadata.
                    cell.setCellValue(text);
                }
            }
            return row;
        }

        void link(Cell cell, String targetSheet, int targetRow) {
            var hyperlink = workbook.getCreationHelper().createHyperlink(HyperlinkType.DOCUMENT);
            hyperlink.setAddress("'" + targetSheet.replace("'", "''") + "'!A" + (targetRow + 1));
            cell.setHyperlink(hyperlink);
            cell.setCellStyle(styles.link);
        }

        void finish() { sheet.setAutoFilter(new CellRangeAddress(2, Math.max(2, nextRow - 1), 0, columns - 1)); }

        private void heading(int index, String text, CellStyle style, float height) {
            Row row = sheet.createRow(index);
            row.setHeightInPoints(height);
            for (int column = 0; column < columns; column++) { row.createCell(column).setCellStyle(style); }
            row.getCell(0).setCellValue(text);
            sheet.addMergedRegion(new CellRangeAddress(index, index, 0, columns - 1));
        }

        private static int width(String header) {
            return switch (header) {
                case "원문" -> 120;
                case "내용", "값" -> 85;
                case "경로", "실행 파일", "분류 근거", "로딩 상태", "SHA-256", "문서" -> 56;
                case "컨테이너 ID", "이미지 ID", "이미지", "조회 상태" -> 42;
                case "시작 시각(UTC)", "이름", "속성", "항목" -> 32;
                default -> 20;
            };
        }
    }

    private static class Styles {
        final CellStyle title;
        final CellStyle subtitle;
        final CellStyle header;
        final CellStyle text;
        final CellStyle number;
        final CellStyle link;

        Styles(Workbook workbook) {
            Font regular = font(workbook, false, 11, IndexedColors.BLACK);
            text = style(workbook, regular, IndexedColors.WHITE);
            number = workbook.createCellStyle();
            number.cloneStyleFrom(text);
            number.setDataFormat(workbook.createDataFormat().getFormat("#,##0"));
            title = style(workbook, font(workbook, true, 18, IndexedColors.WHITE), IndexedColors.DARK_BLUE);
            subtitle = style(workbook, regular, IndexedColors.LIGHT_CORNFLOWER_BLUE);
            header = style(workbook, font(workbook, true, 11, IndexedColors.WHITE), IndexedColors.DARK_TEAL);
            Font linked = font(workbook, false, 11, IndexedColors.BLUE);
            linked.setUnderline(Font.U_SINGLE);
            link = style(workbook, linked, IndexedColors.WHITE);
        }

        private static Font font(Workbook workbook, boolean bold, int size, IndexedColors color) {
            Font font = workbook.createFont();
            font.setFontName("맑은 고딕");
            font.setFontHeightInPoints((short) size);
            font.setBold(bold);
            font.setColor(color.getIndex());
            return font;
        }

        private static CellStyle style(Workbook workbook, Font font, IndexedColors color) {
            CellStyle style = workbook.createCellStyle();
            style.setFont(font);
            style.setWrapText(true);
            style.setVerticalAlignment(VerticalAlignment.TOP);
            style.setFillForegroundColor(color.getIndex());
            style.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            return style;
        }
    }
}
