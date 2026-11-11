package com.wook.observer.platform;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Service;

import javax.net.ssl.HttpsURLConnection;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

@Service
@Profile("!collector")
public class CatalogUpdates {
    private final Store store;
    private final Catalog catalog;
    private final Path directory;
    private final List<String> mirrorHosts;
    public CatalogUpdates(Store store, Catalog catalog, @Value("${observer.platform.update-directory}") String directory,
                          @Value("${observer.platform.mirror-hosts}") String hosts) {
        this.store = store; this.catalog = catalog; this.directory = Path.of(directory).toAbsolutePath().normalize();
        mirrorHosts = Arrays.stream(hosts.split(",")).map(String::strip).filter(s -> !s.isBlank()).map(String::toLowerCase).toList();
    }

    public Map<String, Object> settings() { return store.one("SELECT * FROM update_schedule WHERE singleton = 1"); }
    public List<Map<String, Object>> history() { return store.rows("SELECT * FROM update_run ORDER BY started_at DESC LIMIT 100"); }

    public synchronized void configure(Settings settings) {
        validateSource(settings.source());
        String next = next(settings.cron(), settings.zone());
        store.jdbc.update("UPDATE update_schedule SET enabled = ?, cron = ?, zone = ?, source = ?, next_run = ? WHERE singleton = 1",
                settings.enabled(), settings.cron(), settings.zone(), settings.source(), settings.enabled() ? next : null);
    }

    @Scheduled(fixedDelay = 30000, initialDelay = 30000)
    public synchronized void scheduledUpdate() {
        var row = settings();
        if (!Boolean.TRUE.equals(row.get("enabled"))) { return; }
        String due = Store.string(row, "next_run");
        if (due.isBlank() || Instant.parse(due).isAfter(Instant.now())) { return; }
        int claimed = store.jdbc.update("UPDATE update_schedule SET next_run = ? WHERE singleton = 1 AND next_run = ?",
                next(Store.string(row, "cron"), Store.string(row, "zone")), due);
        if (claimed == 1) {
            try { runNow(); } catch (ApiProblem ignored) { /* Failure is persisted and visible to administrators. */ }
        }
    }

    public synchronized Map<String, Object> runNow() {
        String source = Store.string(settings(), "source");
        byte[] data;
        try { data = load(source); }
        catch (Exception e) {
            String message = "업데이트 원본을 읽지 못했습니다. 반입 파일·미러 허용 호스트·인증서·64MiB 제한을 확인하세요.";
            catalog.recordFailure(source, message);
            throw new ApiProblem(400, message);
        }
        return catalog.importBytes(data, source);
    }

    private byte[] load(String source) throws IOException {
        validateSource(source);
        if (!source.startsWith("https://")) {
            Path root = directory.toRealPath();
            Path file = root.resolve(source).toRealPath();
            if (!file.startsWith(root) || !Files.isRegularFile(file) || Files.size(file) > Catalog.MAX_UPLOAD) { throw new IOException("Invalid update file"); }
            try (var input = Files.newInputStream(file)) { return bounded(input.readNBytes(Catalog.MAX_UPLOAD + 1)); }
        }
        HttpsURLConnection connection = (HttpsURLConnection) URI.create(source).toURL().openConnection();
        connection.setInstanceFollowRedirects(false);
        connection.setConnectTimeout(10000);
        connection.setReadTimeout(15000);
        connection.setRequestProperty("Accept", "application/json, application/zip");
        try {
            if (connection.getResponseCode() != 200 || connection.getContentLengthLong() > Catalog.MAX_UPLOAD) { throw new IOException("Mirror response rejected"); }
            try (var input = connection.getInputStream()) { return bounded(input.readNBytes(Catalog.MAX_UPLOAD + 1)); }
        } finally { connection.disconnect(); }
    }

    private byte[] bounded(byte[] bytes) throws IOException {
        if (bytes.length > Catalog.MAX_UPLOAD) { throw new IOException("Update exceeds size limit"); }
        return bytes;
    }

    private void validateSource(String source) {
        if (source == null || source.length() > 1000) { throw new ApiProblem(400, "업데이트 원본을 확인하세요."); }
        if (source.matches("[A-Za-z0-9][A-Za-z0-9_.-]{0,199}\\.(json|zip)")) { return; }
        try {
            URI uri = URI.create(source);
            if ("https".equals(uri.getScheme()) && uri.getHost() != null && mirrorHosts.contains(uri.getHost().toLowerCase())
                    && uri.getUserInfo() == null && uri.getQuery() == null && uri.getFragment() == null
                    && (uri.getPort() == -1 || uri.getPort() == 443)) { return; }
        } catch (IllegalArgumentException ignored) { }
        throw new ApiProblem(400, "반입 폴더의 JSON/ZIP 파일명 또는 OBSERVER_MIRROR_HOSTS에 허용된 HTTPS URL을 입력하세요.");
    }

    private String next(String cron, String zone) {
        try {
            if (cron.length() > 100 || zone.length() > 80) { throw new IllegalArgumentException(); }
            var time = CronExpression.parse(cron).next(ZonedDateTime.now(ZoneId.of(zone)));
            if (time == null) { throw new IllegalArgumentException(); }
            return time.toInstant().toString();
        } catch (Exception e) { throw new ApiProblem(400, "유효한 6필드 cron 식과 시간대를 입력하세요."); }
    }

    public record Settings(boolean enabled, String cron, String zone, String source) { }
}
