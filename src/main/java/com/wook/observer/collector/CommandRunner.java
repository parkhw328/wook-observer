package com.wook.observer.collector;

import com.wook.observer.config.ObserverProperties;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

@Component
public class CommandRunner {
    private static final int MAX_OUTPUT = 1024 * 1024;
    private final ObserverProperties properties;

    public CommandRunner(ObserverProperties properties) {
        this.properties = properties;
    }

    public Result run(List<String> command) {
        return run(command, "", properties.getCommandTimeout(), MAX_OUTPUT);
    }

    Result run(List<String> command, String input, Duration timeout, int maxOutput) {
        Process process = null;
        try {
            process = new ProcessBuilder(command).redirectErrorStream(true).start();
            Process running = process;
            CompletableFuture<byte[]> output = new CompletableFuture<>();
            Thread.ofVirtual().start(() -> {
                try (var stream = running.getInputStream()) {
                    output.complete(stream.readNBytes(maxOutput + 1));
                } catch (IOException exception) {
                    output.completeExceptionally(exception);
                }
            });
            try (var stdin = process.getOutputStream()) {
                stdin.write(input.getBytes(StandardCharsets.UTF_8));
            }
            if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                return new Result(false, "", "조회 명령 시간 제한 초과");
            }
            byte[] bytes = output.get(1, TimeUnit.SECONDS);
            if (bytes.length > maxOutput) {
                return new Result(false, "", "조회 출력 크기 제한 초과");
            }
            if (process.exitValue() != 0) {
                return new Result(false, "", "조회 실패(exit=" + process.exitValue() + "); 권한·대상 상태·도구 호환성 확인 필요");
            }
            return new Result(true, new String(bytes, StandardCharsets.UTF_8), "");
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return new Result(false, "", "조회 중단");
        } catch (Exception exception) {
            return new Result(false, "", "조회 도구 실행 또는 출력 읽기 불가 (" + exception.getClass().getSimpleName() + ")");
        } finally {
            if (process != null) {
                process.descendants().forEach(ProcessHandle::destroyForcibly);
                if (process.isAlive()) {
                    process.destroyForcibly();
                }
                try {
                    process.getInputStream().close();
                } catch (IOException ignored) {
                    // The command has already exited; no raw command output is logged.
                }
            }
        }
    }

    public record Result(boolean success, String output, String failure) { }
}
