package com.wook.observer.platform;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.Map;
import java.util.concurrent.TimeUnit;

/** Structured operational fields only: never pass request bodies, credentials or command output here. */
public final class OperationLog {
    private static final Logger LOG = LoggerFactory.getLogger("com.wook.observer.operations");
    private OperationLog() { }

    public static void info(String event, Object... fields) { LOG.info(message(event, fields)); }
    public static void warn(String event, Object... fields) { LOG.warn(message(event, fields)); }
    public static void debug(String event, Object... fields) { if (LOG.isDebugEnabled()) LOG.debug(message(event, fields)); }
    public static long elapsed(long start) { return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start); }

    public static void failure(String event, Throwable error, Object... fields) {
        String detail = message(event, fields) + " errorType=" + error.getClass().getSimpleName();
        if (error instanceof ApiProblem problem) detail += " status=" + problem.status;
        if (error.getStackTrace().length > 0) detail += " origin=" + token(error.getStackTrace()[0]);
        if (error instanceof ApiProblem problem && problem.status < 500) LOG.warn(detail); else LOG.error(detail);
        // Exception messages may contain SQL parameters, remote output or credentials. Log code locations only.
        if (LOG.isDebugEnabled()) {
            var trace = new StringBuilder();
            for (int depth = 0; error != null && depth < 4; depth++, error = error.getCause()) {
                trace.append(error.getClass().getName()).append(':');
                var frames = error.getStackTrace();
                for (int i = 0; i < Math.min(12, frames.length); i++) trace.append(' ').append(frames[i]);
                trace.append(" | ");
            }
            LOG.debug("event=exception.trace codeLocations={}", trace);
        }
    }

    public static void afterCommit(String event, Object... fields) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) { info(event, fields); return; }
        var context = MDC.getCopyOfContextMap();
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void afterCommit() { try (var ignored = context(context)) { info(event, fields); } }
        });
    }

    public static Scope context(Map<String, String> context) { return new Scope(context); }
    public static final class Scope implements AutoCloseable {
        private final Map<String, String> previous = MDC.getCopyOfContextMap();
        private Scope(Map<String, String> values) { if (values == null) MDC.clear(); else MDC.setContextMap(values); }
        @Override public void close() { if (previous == null) MDC.clear(); else MDC.setContextMap(previous); }
    }

    private static String message(String event, Object... fields) {
        var result = new StringBuilder("event=").append(token(event));
        for (int i = 0; i + 1 < fields.length; i += 2) result.append(' ').append(fields[i]).append('=').append(token(fields[i + 1]));
        return result.toString();
    }
    static String token(Object value) {
        String text = value == null ? "-" : String.valueOf(value);
        text = text.substring(0, Math.min(text.length(), 400)).replaceAll("[\\p{Cntrl}\\p{Zl}\\p{Zp}\\p{Cf}]", "_");
        return text.matches("[A-Za-z0-9_./:@+-]+") ? text : '"' + text.replace("\\", "\\\\").replace("\"", "\\\"") + '"';
    }
    public static String source(String source) {
        if (source != null && source.matches("[A-Za-z0-9][A-Za-z0-9_.-]{0,199}\\.(zip|json)")) return source;
        return source != null && source.startsWith("https://") ? "https-mirror" : "upload-or-bootstrap";
    }
}
