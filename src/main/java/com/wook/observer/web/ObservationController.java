package com.wook.observer.web;

import com.wook.observer.collector.ObservationService;
import com.wook.observer.model.Observation;
import com.wook.observer.report.ReportWriter;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.util.Map;

@RestController
@RequestMapping("/api/v1")
public class ObservationController {
    private final ObservationService observations;
    private final ReportWriter reports;

    public ObservationController(ObservationService observations, ReportWriter reports) {
        this.observations = observations;
        this.reports = reports;
    }

    @PostMapping(value = "/observations", produces = MediaType.APPLICATION_JSON_VALUE)
    public Observation observe() { return observations.collect(); }

    @PostMapping(value = "/reports", produces = "application/zip")
    public ResponseEntity<byte[]> report() throws IOException {
        return ResponseEntity.ok().header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"wook-observer-report.zip\"")
                .header(HttpHeaders.CACHE_CONTROL, "no-store").contentType(MediaType.parseMediaType("application/zip"))
                .body(reports.zip(observations.collect()));
    }

    @ExceptionHandler(ObservationService.ScanInProgressException.class)
    public ResponseEntity<Map<String, String>> scanInProgress(ObservationService.ScanInProgressException exception) {
        return ResponseEntity.status(HttpStatus.CONFLICT).contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("message", exception.getMessage()));
    }
}
