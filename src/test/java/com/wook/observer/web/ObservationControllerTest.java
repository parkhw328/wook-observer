package com.wook.observer.web;

import com.wook.observer.ReportFixtures;
import com.wook.observer.collector.ObservationService;
import com.wook.observer.config.ObserverProperties;
import com.wook.observer.model.ServiceCategory;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.net.URI;
import java.io.ByteArrayInputStream;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.config.import=classpath:service-overrides.yml")
@org.springframework.test.context.ActiveProfiles("collector")
class ObservationControllerTest {
    @Value("${local.server.port}") int port;
    @MockitoBean ObservationService observations;
    @Autowired ObserverProperties properties;
    private final HttpClient http = HttpClient.newHttpClient();

    @Test
    void bindsServiceOverrideKeysWithoutDroppingTheColon() {
        assertThat(properties.getServiceOverrides()).containsEntry("container:orders", ServiceCategory.MESSAGING);
    }

    @Test
    void servesHealthAndKeepsSensitiveActuatorEndpointsUnexposed() throws Exception {
        var health = http.send(HttpRequest.newBuilder(uri("/actuator/health")).GET().build(), HttpResponse.BodyHandlers.ofString());
        assertThat(health.statusCode()).isEqualTo(200);
        assertThat(health.body()).contains("UP");
        var environment = http.send(HttpRequest.newBuilder(uri("/actuator/env")).GET().build(), HttpResponse.BodyHandlers.ofString());
        assertThat(environment.statusCode()).isEqualTo(404);
    }

    @Test
    void returnsStructuredSnapshotAndDownloadableSpecifications() throws Exception {
        when(observations.collect()).thenReturn(ReportFixtures.sample());
        var observation = http.send(post("/api/v1/observations"), HttpResponse.BodyHandlers.ofString());
        assertThat(observation.statusCode()).isEqualTo(200);
        assertThat(observation.body()).contains("javaProcesses", "21.0.13");
        var report = http.send(post("/api/v1/reports"), HttpResponse.BodyHandlers.ofByteArray());
        assertThat(report.statusCode()).isEqualTo(200);
        assertThat(report.headers().firstValue("Content-Type")).contains("application/zip");
        assertThat(report.body()).startsWith((byte) 'P', (byte) 'K');
    }

    @Test
    void downloadsAStandaloneExcelWorkbook() throws Exception {
        when(observations.collect()).thenReturn(ReportFixtures.sample());
        var response = http.send(post("/api/v1/reports.xlsx"), HttpResponse.BodyHandlers.ofByteArray());
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("Content-Type"))
                .contains("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        assertThat(response.headers().firstValue("Content-Disposition").orElseThrow()).contains("wook-observer-report.xlsx");
        assertThat(response.headers().firstValue("Cache-Control")).contains("no-store");
        try (var workbook = new XSSFWorkbook(new ByteArrayInputStream(response.body()))) {
            assertThat(workbook.getNumberOfSheets()).isEqualTo(30);
            assertThat(workbook.getSheet("JSON 원문")).isNotNull();
        }
    }

    @Test
    void concurrentScanReturnsConflict() throws Exception {
        when(observations.collect()).thenThrow(new ObservationService.ScanInProgressException());
        assertThat(http.send(post("/api/v1/observations"), HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(409);
        assertThat(http.send(post("/api/v1/reports.xlsx"), HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(409);
    }

    private HttpRequest post(String path) { return HttpRequest.newBuilder(uri(path)).POST(HttpRequest.BodyPublishers.noBody()).build(); }
    private URI uri(String path) { return URI.create("http://127.0.0.1:" + port + path); }
}
