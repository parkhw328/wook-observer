package com.wook.observer.platform;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Profile;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.HandlerMapping;

import java.io.IOException;
import java.util.Map;
import java.util.UUID;

@Component
@Profile("!collector")
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
public class RequestLogging extends OncePerRequestFilter {
    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain) throws ServletException, IOException {
        boolean api = request.getRequestURI().startsWith("/api/");
        String requestId = UUID.randomUUID().toString();
        long started = System.nanoTime();
        try (var ignored = OperationLog.context(Map.of("requestId", requestId, "actor", "anonymous"))) {
            if (api) response.setHeader("X-Request-ID", requestId);
            boolean failed = false;
            try { chain.doFilter(request, response); }
            catch (IOException | ServletException | RuntimeException error) {
                failed = true;
                if (api) OperationLog.failure("http.unhandled", error, "method", request.getMethod(), "route", route(request));
                throw error;
            } finally {
                if (api) {
                    int status = failed ? 500 : response.getStatus();
                    Object[] fields = {"method", request.getMethod(), "route", route(request), "status", status, "durationMs", OperationLog.elapsed(started)};
                    if (status >= 400) OperationLog.warn("http.completed", fields);
                    else if (!"GET".equals(request.getMethod()) || route(request).contains("download") || route(request).contains("export.xlsx")) OperationLog.info("http.completed", fields);
                    else OperationLog.debug("http.completed", fields);
                }
            }
        }
    }

    private String route(HttpServletRequest request) {
        Object matched = request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
        if (matched != null) return matched.toString();
        // Security may reject a request before MVC chooses its route. Never log raw paths or query strings.
        return switch (request.getRequestURI()) {
            case "/api/login", "/api/logout", "/api/session" -> request.getRequestURI();
            default -> "/api/(unmatched)";
        };
    }
}
