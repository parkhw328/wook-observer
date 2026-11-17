package com.wook.observer.platform;

import com.wook.observer.collector.CollectionScripts;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;

@RestController
@Profile("!collector")
@RequestMapping("/api")
public class OfflineController {
    private final OfflineImports imports;
    private final Targets targets;
    public OfflineController(OfflineImports imports, Targets targets) { this.imports = imports; this.targets = targets; }

    @GetMapping("/collector/observer-collect.sh")
    public ResponseEntity<byte[]> script() throws IOException {
        byte[] bytes = CollectionScripts.offline().getBytes(StandardCharsets.UTF_8);
        OperationLog.info("offline.script.downloaded", "bytes", bytes.length);
        return ResponseEntity.ok().contentType(MediaType.parseMediaType("text/x-shellscript;charset=UTF-8"))
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"observer-collect.sh\"").body(bytes);
    }

    @PostMapping("/admin/offline/targets")
    public Map<String, String> add(@RequestBody Targets.UploadInput input, Authentication auth) {
        return Map.of("id", targets.saveUpload(null, input, auth.getName()));
    }
    @PutMapping("/admin/offline/targets/{id}")
    public Map<String, String> edit(@PathVariable String id, @RequestBody Targets.UploadInput input, Authentication auth) {
        return Map.of("id", targets.saveUpload(id, input, auth.getName()));
    }
    @PostMapping(value = "/admin/offline/preview", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Map<String, Object> preview(@RequestParam("file") MultipartFile file, @RequestParam(defaultValue = "") String targetId) throws IOException {
        return imports.preview(read(file), targetId);
    }
    @PostMapping(value = "/admin/offline/import", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Map<String, Object> upload(@RequestParam("file") MultipartFile file, @RequestParam(defaultValue = "") String targetId,
                                     @RequestParam(defaultValue = "") String name, @RequestParam(defaultValue = "") String host,
                                     @RequestParam(defaultValue = "false") boolean confirmIdentity, Authentication auth) throws IOException {
        return imports.importFile(read(file), targetId, name, host, confirmIdentity, auth.getName());
    }
    private byte[] read(MultipartFile file) throws IOException {
        if (file.getSize() > OfflineInventory.MAX_BYTES) throw new ApiProblem(400, "수집 결과 파일 제한은 16 MiB입니다.");
        try (var input = file.getInputStream()) {
            byte[] bytes = input.readNBytes(OfflineInventory.MAX_BYTES + 1);
            if (bytes.length > OfflineInventory.MAX_BYTES) throw new ApiProblem(400, "수집 결과 파일 제한은 16 MiB입니다.");
            return bytes;
        }
    }
}
