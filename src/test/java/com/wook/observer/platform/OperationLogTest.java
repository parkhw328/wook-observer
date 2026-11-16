package com.wook.observer.platform;

import ch.qos.logback.classic.Level;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class OperationLogTest {
    @Test
    void keepsFieldsOnOneLineAndNeverLogsExceptionMessagesEvenInDebug() {
        try (var log = new OperationLogCapture(Level.DEBUG)) {
            OperationLog.info("test.input", "value", "line\n\r\u001b\u202e\"\\value");
            OperationLog.failure("test.failed", new IllegalStateException("credential-canary", new RuntimeException("sql-secret-canary")));
            assertThat(log.events.getFirst().getFormattedMessage()).doesNotContain("\n", "\r", "\u001b", "\u202e").contains("\\\"", "\\\\");
            assertThat(log.text()).contains("errorType=IllegalStateException", "event=exception.trace", "OperationLogTest.java", "java.lang.RuntimeException")
                    .doesNotContain("credential-canary", "sql-secret-canary");
            assertThat(log.events).allSatisfy(event -> assertThat(event.getThrowableProxy()).isNull());
            assertThat(OperationLog.token("a".repeat(1000))).hasSize(400);
            assertThat(OperationLog.source("https://mirror.invalid/secret?token=canary")).isEqualTo("https-mirror");
        }
    }

    @Test
    void restoresContextAndUnexpectedErrorsReturnOnlySupportId() {
        var before = MDC.getCopyOfContextMap();
        try (var outer = OperationLog.context(Map.of("requestId", "request-1", "actor", "admin"));
             var log = new OperationLogCapture(Level.INFO)) {
            try (var inner = OperationLog.context(Map.of("jobId", "job-1"))) {
                assertThat(MDC.get("requestId")).isNull();
                assertThat(MDC.get("jobId")).isEqualTo("job-1");
            }
            assertThat(MDC.get("requestId")).isEqualTo("request-1");
            assertThat(MDC.get("jobId")).isNull();
            var error = new PlatformErrors().unexpected(new IllegalStateException("connection-password-canary"));
            assertThat(error.getStatusCode().value()).isEqualTo(500);
            assertThat(error.getBody()).containsEntry("requestId", "request-1");
            assertThat(error.getBody().toString()).doesNotContain("connection-password-canary");
            assertThat(log.text()).doesNotContain("connection-password-canary", "exception.trace");
        }
        assertThat(MDC.getCopyOfContextMap()).isEqualTo(before);
    }

    @Test
    void classifiesSshDiagnosticsWithoutReturningRawRemoteOutputAndDrainsLargeStreams() throws Exception {
        var samples = Map.of("Host key verification failed.", "HOST_KEY_REJECTED", "Permission denied (publickey,password).", "AUTHENTICATION_FAILED",
                "Could not resolve hostname", "DNS_FAILED", "connect: Connection refused", "CONNECTION_REFUSED", "Connection timed out", "CONNECT_TIMEOUT",
                "No route to host", "NETWORK_UNREACHABLE", "Load key: error in libcrypto", "KEY_FORMAT", "remote-secret-canary", "REMOTE_EXIT_OR_PRIVILEGE_FAILURE");
        samples.forEach((text, code) -> assertThat(SshTransport.diagnosticCode(text + " remote-secret-canary")).isEqualTo(code));
        var input = new ByteArrayInputStream(("Connection refused\n" + "remote-secret-canary".repeat(10000)).getBytes(StandardCharsets.UTF_8));
        assertThat(SshTransport.diagnostic(input)).isEqualTo("CONNECTION_REFUSED");
        assertThat(input.available()).isZero();
    }
}
