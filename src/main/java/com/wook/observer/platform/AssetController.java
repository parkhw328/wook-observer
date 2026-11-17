package com.wook.observer.platform;

import org.springframework.context.annotation.Profile;
import org.springframework.security.core.Authentication;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

@RestController
@Profile("!collector")
@RequestMapping("/api")
public class AssetController {
    private final Assets assets;
    private final AssetTrash trash;
    public AssetController(Assets assets, AssetTrash trash) { this.assets = assets; this.trash = trash; }

    @GetMapping("/admin/assets/trash") public Map<String, Object> trash() { return trash.list(); }
    @DeleteMapping("/admin/assets/{target}")
    public Map<String, String> delete(@PathVariable String target, @RequestBody AssetTrash.AssetInput input, Authentication auth) {
        trash.deleteAsset(target, input, auth.getName());
        return Map.of("message", "자산을 휴지통으로 옮겼습니다. 자산 휴지통에서 복원할 수 있습니다.");
    }
    @PostMapping("/admin/assets/{target}/restore")
    public Map<String, String> restore(@PathVariable String target, @RequestBody AssetTrash.RevisionInput input, Authentication auth) {
        trash.restoreAsset(target, input, auth.getName());
        return Map.of("message", "자산과 점검 이력을 복원했습니다.");
    }
    @DeleteMapping("/admin/assets/{target}/services/{service}")
    public Map<String, String> deleteService(@PathVariable String target, @PathVariable String service, @RequestBody AssetTrash.RevisionInput input, Authentication auth) {
        trash.deleteService(target, service, input, auth.getName());
        return Map.of("message", "서비스를 휴지통으로 옮겼습니다.");
    }
    @PostMapping("/admin/assets/{target}/services/{service}/restore")
    public Map<String, String> restoreService(@PathVariable String target, @PathVariable String service, @RequestBody AssetTrash.RevisionInput input, Authentication auth) {
        trash.restoreService(target, service, input, auth.getName());
        return Map.of("message", "서비스를 복원했습니다. 기존 숨김 설정은 유지됩니다.");
    }

    @GetMapping("/assets") public Map<String, Object> list() { return assets.list(); }
    @GetMapping("/assets/{target}") public Map<String, Object> detail(@PathVariable String target) { return assets.details(target); }
    @GetMapping({"/assets/report.md", "/assets/{target}/report.md"})
    public ResponseEntity<byte[]> markdown(@PathVariable(required = false) String target) {
        long started = System.nanoTime();
        byte[] bytes = assets.markdown(target).getBytes(java.nio.charset.StandardCharsets.UTF_8);
        OperationLog.info("asset.exported", "targetId", target == null ? "ALL" : target, "format", "md", "bytes", bytes.length, "durationMs", OperationLog.elapsed(started));
        return ResponseEntity.ok().contentType(MediaType.parseMediaType("text/markdown;charset=UTF-8"))
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"observer-assets.md\"").body(bytes);
    }
    @GetMapping({"/assets/export.xlsx", "/assets/{target}/export.xlsx"})
    public ResponseEntity<byte[]> export(@PathVariable(required = false) String target) throws IOException {
        long started = System.nanoTime();
        var output = new ByteArrayOutputStream();
        assets.export(target, output);
        OperationLog.info("asset.exported", "targetId", target == null ? "ALL" : target, "format", "xlsx", "bytes", output.size(), "durationMs", OperationLog.elapsed(started));
        return ResponseEntity.ok().contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"observer-assets.xlsx\"")
                .body(output.toByteArray());
    }
    @PutMapping("/admin/assets/{target}")
    public Map<String, String> profile(@PathVariable String target, @RequestBody Assets.ProfileInput input, Authentication auth) {
        assets.saveProfile(target, input, auth.getName());
        return Map.of("message", "자산 정보를 저장했습니다.");
    }
    @PostMapping("/admin/assets/{target}/services")
    public Map<String, String> add(@PathVariable String target, @RequestBody Assets.ServiceInput input, Authentication auth) {
        return Map.of("id", assets.saveService(target, null, input, auth.getName()));
    }
    @PutMapping("/admin/assets/{target}/services/{service}")
    public Map<String, String> edit(@PathVariable String target, @PathVariable String service, @RequestBody Assets.ServiceInput input, Authentication auth) {
        return Map.of("id", assets.saveService(target, service, input, auth.getName()));
    }
}
