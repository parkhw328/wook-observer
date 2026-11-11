package com.wook.observer.platform;

import com.wook.observer.model.Observation;
import com.wook.observer.report.ReportWriter;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;
import java.util.Map;

@RestController
@Profile("!collector")
@RequestMapping("/api")
public class PlatformController {
    private final Accounts accounts;
    private final Targets targets;
    private final ScanJobs jobs;
    private final Catalog catalog;
    private final CatalogUpdates updates;
    private final ReportWriter reports;
    private final HostKeys hostKeys;
    public PlatformController(Accounts accounts, Targets targets, ScanJobs jobs, Catalog catalog, CatalogUpdates updates, ReportWriter reports, HostKeys hostKeys) {
        this.accounts = accounts; this.targets = targets; this.jobs = jobs; this.catalog = catalog; this.updates = updates; this.reports = reports; this.hostKeys = hostKeys;
    }

    @GetMapping("/session")
    public Map<String, Object> session(Authentication auth, CsrfToken csrf) {
        String role = auth == null ? "" : auth.getAuthorities().stream().findFirst().map(Object::toString).orElse("").replace("ROLE_", "");
        return Map.of("username", auth == null ? "" : auth.getName(), "role", role, "csrf", csrf.getToken(), "csrfHeader", csrf.getHeaderName(), "version", "1.2.0");
    }
    @GetMapping("/targets") public List<Map<String, Object>> targets() { return targets.list(); }
    @GetMapping("/admin/targets/{id}") public Map<String, Object> target(@PathVariable String id) {
        return targets.details(id);
    }
    @PostMapping("/admin/ssh/host-keys") public List<HostKeys.Key> hostKeys(@RequestBody HostKeys.Endpoint endpoint) throws Exception { return hostKeys.probe(endpoint); }
    @PostMapping("/admin/targets") public Map<String, String> addTarget(@RequestBody Targets.Input input, Authentication auth) { return Map.of("id", targets.save(null, input, auth.getName())); }
    @PutMapping("/admin/targets/{id}") public Map<String, String> editTarget(@PathVariable String id, @RequestBody Targets.Input input, Authentication auth) { return Map.of("id", targets.save(id, input, auth.getName())); }
    @GetMapping("/jobs") public List<Map<String, Object>> jobs() { return jobs.list(); }
    @PostMapping("/targets/{id}/scans") public ResponseEntity<Map<String, String>> scan(@PathVariable String id, Authentication auth) {
        return ResponseEntity.accepted().body(Map.of("id", jobs.start(id, auth.getName())));
    }
    @GetMapping("/jobs/{id}") public Observation report(@PathVariable String id) { return jobs.report(id); }
    @GetMapping("/jobs/{id}/download/{format}")
    public ResponseEntity<byte[]> download(@PathVariable String id, @PathVariable String format) throws IOException {
        var report = jobs.report(id);
        byte[] bytes;
        String type;
        switch (format) {
            case "xlsx" -> { bytes = reports.xlsx(report); type = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"; }
            case "zip" -> { bytes = reports.zip(report); type = "application/zip"; }
            case "json" -> { bytes = reports.documents(report).get("report.json").getBytes(java.nio.charset.StandardCharsets.UTF_8); type = "application/json"; }
            case "md" -> { bytes = reports.documents(report).get("summary.md").getBytes(java.nio.charset.StandardCharsets.UTF_8); type = "text/markdown;charset=UTF-8"; }
            default -> throw new ApiProblem(400, "지원 형식: xlsx, zip, json, md");
        }
        return ResponseEntity.ok().contentType(MediaType.parseMediaType(type)).header(HttpHeaders.CACHE_CONTROL, "no-store")
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"wook-observer-" + id + "." + format + "\"").body(bytes);
    }
    @GetMapping("/catalog/status") public Map<String, Object> catalogStatus() { return catalog.status(); }
    @GetMapping("/catalog") public List<Map<String, Object>> catalog(@RequestParam(defaultValue = "") String query, @RequestParam(defaultValue = "0") int page) {
        if (query.length() > 200) { throw new ApiProblem(400, "검색어는 200자 이내로 입력하세요."); }
        return catalog.search(query, page);
    }
    @PostMapping(value = "/admin/catalog/import", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Map<String, Object> upload(@RequestParam("file") MultipartFile file) throws IOException {
        if (file.getSize() > Catalog.MAX_UPLOAD) { throw new ApiProblem(400, "파일 제한은 64MiB입니다."); }
        return catalog.importBytes(file.getBytes(), "upload:" + java.util.Objects.toString(file.getOriginalFilename(), "catalog"));
    }
    @GetMapping("/admin/updates") public Map<String, Object> updates() { return Map.of("settings", updates.settings(), "history", updates.history()); }
    @PutMapping("/admin/updates") public Map<String, String> configure(@RequestBody CatalogUpdates.Settings input) { updates.configure(input); return Map.of("message", "업데이트 설정 저장 완료"); }
    @PostMapping("/admin/updates/run") public Map<String, Object> updateNow() { return updates.runNow(); }
    @GetMapping("/admin/users") public List<Map<String, Object>> users() { return accounts.list(); }
    @PostMapping("/admin/users") public Map<String, String> createUser(@RequestBody UserInput input) { accounts.create(input.username(), input.password(), input.role(), input.enabled() == null || input.enabled()); return Map.of("message", "사용자 생성 완료"); }
    @PutMapping("/admin/users/{username}") public Map<String, String> changeUser(@PathVariable String username, @RequestBody UserInput input) {
        if (input.enabled() == null) { throw new ApiProblem(400, "계정 활성화 여부가 필요합니다."); }
        accounts.update(username, input.password(), input.role(), input.enabled()); return Map.of("message", "계정 변경 완료. 기존 로그인 세션이 만료됩니다.");
    }
    public record UserInput(String username, String password, String role, Boolean enabled) { }
}
