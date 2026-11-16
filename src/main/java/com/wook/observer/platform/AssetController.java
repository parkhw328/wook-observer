package com.wook.observer.platform;

import org.springframework.context.annotation.Profile;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@Profile("!collector")
@RequestMapping("/api")
public class AssetController {
    private final Assets assets;
    public AssetController(Assets assets) { this.assets = assets; }

    @GetMapping("/assets") public Map<String, Object> list() { return assets.list(); }
    @GetMapping("/assets/{target}") public Map<String, Object> detail(@PathVariable String target) { return assets.details(target); }
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
