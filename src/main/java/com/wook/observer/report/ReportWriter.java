package com.wook.observer.report;

import com.wook.observer.model.Observation;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

@Component
public class ReportWriter {
    private final MarkdownReports markdown;
    private final ExcelReports excel;
    private final JsonMapper json = JsonMapper.builder().build();

    public ReportWriter(MarkdownReports markdown, ExcelReports excel) {
        this.markdown = markdown;
        this.excel = excel;
    }

    public Map<String, String> documents(Observation report) {
        Map<String, String> documents = new LinkedHashMap<>(markdown.documents(report));
        documents.put("report.json", json.writerWithDefaultPrettyPrinter().writeValueAsString(report) + "\n");
        return documents;
    }

    public Path writeDirectory(Observation report, Path destination) throws IOException {
        Map<String, String> documents = documents(report);
        Path directory = destination.toAbsolutePath().normalize();
        if (directory.getParent() != null) { Files.createDirectories(directory.getParent()); }
        Files.createDirectory(directory);
        for (var entry : documents.entrySet()) {
            Path file = directory.resolve(entry.getKey());
            Files.createDirectories(file.getParent());
            Files.writeString(file, entry.getValue(), StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
        }
        try (var output = Files.newOutputStream(directory.resolve("report.xlsx"), StandardOpenOption.CREATE_NEW)) {
            excel.write(report, documents, output);
        }
        return directory;
    }

    public byte[] zip(Observation report) throws IOException {
        Map<String, String> documents = documents(report);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes, StandardCharsets.UTF_8)) {
            for (var entry : documents.entrySet()) {
                zip.putNextEntry(new ZipEntry(entry.getKey()));
                zip.write(entry.getValue().getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
            zip.putNextEntry(new ZipEntry("report.xlsx"));
            excel.write(report, documents, zip);
            zip.closeEntry();
        }
        return bytes.toByteArray();
    }

    public byte[] xlsx(Observation report) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        excel.write(report, documents(report), bytes);
        return bytes.toByteArray();
    }
}
