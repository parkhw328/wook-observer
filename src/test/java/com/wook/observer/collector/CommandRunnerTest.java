package com.wook.observer.collector;

import com.wook.observer.config.ObserverProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CommandRunnerTest {
    @Test
    void readsSuccessfulCommandOutput() {
        var result = new CommandRunner(new ObserverProperties()).run(List.of(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(), "-version"));
        assertThat(result.success()).isTrue();
        assertThat(result.output()).contains("21.");
    }

    @Test
    @EnabledOnOs(OS.LINUX)
    void terminatesOnlyTheSpawnedCommandWhenItTimesOut() {
        var properties = new ObserverProperties();
        properties.setCommandTimeout(Duration.ofMillis(150));
        long started = System.nanoTime();
        var result = new CommandRunner(properties).run(List.of("/bin/sleep", "5"));
        assertThat(result.success()).isFalse();
        assertThat(result.failure()).contains("시간 제한");
        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(3));
    }

    @Test
    void reportsMissingToolWithoutExposingRawCommandArguments() {
        var result = new CommandRunner(new ObserverProperties()).run(List.of("/nonexistent/wook-tool", "private-token"));
        assertThat(result.success()).isFalse();
        assertThat(result.output()).isEmpty();
        assertThat(result.failure()).doesNotContain("private-token");
    }
}
