package com.wook.observer.report;

import com.wook.observer.collector.SystemInventoryParser;
import com.wook.observer.model.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import java.io.*;
import java.time.Instant;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class NetworkReportTest {
    @Test
    void exportsAllPeersSettingsAndUnknownOwnersAsLiteralEvidence() throws Exception {
        var parser = new SystemInventoryParser();
        List.of("CONNECTION\ttcp\tESTAB 0 0 [::1]:8443 [::1]:44000 users:((\"<img src=x>\",pid=42,fd=5))",
                "CONNECTION\ttcp\tTIME-WAIT 0 0 192.0.2.1:44001 192.0.2.2:443", "CONNECTION_STATUS\tCOLLECTED",
                "NET_SETTING\tip_local_reserved_ports\tCOLLECTED\t8443").forEach(parser::accept);
        var report = new Observation("1.12.0",Instant.now(),null,List.of(),List.of(),List.of(),List.of(),Map.of(),null,parser.build(new ArrayList<>()));
        var network = NetworkObservation.from(report);
        var asset = Map.<String,Object>of("id","net-1","name","네트워크 시험");
        String markdown = AssetMarkdown.write(List.of(asset),a -> List.of(),a -> network);
        assertThat(markdown).contains("&#91;::1&#93;", "44000", "44001", "ESTABLISHED", "로컬 예약 포트", "미수집", "PID 42").doesNotContain("<img");
        var output = new ByteArrayOutputStream();
        new AssetWorkbook().write(List.of(asset),a -> List.of(),a -> network,output);
        try (var book = new XSSFWorkbook(new ByteArrayInputStream(output.toByteArray()))) {
            var rows = book.getSheet("네트워크 관측");
            assertThat(rows.getLastRowNum()).isEqualTo(2);
            assertThat(rows.getRow(1).getCell(6).getStringCellValue()).isEqualTo("[::1]:44000");
            assertThat(rows.getRow(1).getCell(7).getStringCellValue()).contains("<img src=x>");
            assertThat(book.getSheet("네트워크 수집 범위").getLastRowNum()).isEqualTo(3);
        }
    }
}
