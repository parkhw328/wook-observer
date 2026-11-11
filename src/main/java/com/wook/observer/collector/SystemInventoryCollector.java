package com.wook.observer.collector;

import com.wook.observer.model.Observation.Issue;
import com.wook.observer.model.SystemInventory;
import com.wook.observer.model.SystemInventory.Status;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;

@Component
public class SystemInventoryCollector {
    private final CommandRunner commands;
    public SystemInventoryCollector(CommandRunner commands) { this.commands = commands; }

    public static String script() throws IOException {
        try (var resource = new ClassPathResource("collector/system-inventory.sh").getInputStream()) {
            return new String(resource.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    public SystemInventory collect(List<Issue> issues) {
        var parser = new SystemInventoryParser();
        try {
            if (!System.getProperty("os.name").equalsIgnoreCase("Linux")) {
                throw new IOException("Unsupported OS");
            }
            var result = commands.run(List.of("sh", "-s"), script(), Duration.ofSeconds(30), 5 * 1024 * 1024);
            if (!result.success()) {
                issues.add(new Issue("os", "local inventory", result.failure()));
                return failed();
            }
            result.output().lines().forEach(parser::accept);
            return parser.build(issues);
        } catch (IOException exception) {
            issues.add(new Issue("os", "local inventory", "Linux 기본 정보 수집 도구를 실행할 수 없습니다."));
            return failed();
        }
    }

    private SystemInventory failed() {
        return new SystemInventory(List.of(), new SystemInventory.RpmInventory(Status.FAILED, "OS 수집기 실행 실패", List.of()));
    }
}
