package com.soarer.alert.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class ObservabilityFilter extends OncePerRequestFilter {

    private static final Pattern RUN_PATH_PATTERN =
            Pattern.compile("^/api/ai_ops/runs/([0-9a-fA-F-]{36})(/.*)?$");

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        String traceId = normalize(request.getHeader("X-Trace-Id"), 64);
        if (traceId == null) {
            traceId = UUID.randomUUID().toString().replace("-", "");
        }
        String sessionId = normalize(request.getHeader("X-Session-Id"), 128);
        String runId = normalize(request.getHeader("X-Run-Id"), 36);
        if (runId == null) {
            runId = extractRunId(request.getRequestURI());
        }

        MDC.put("traceId", traceId);
        putIfPresent("sessionId", sessionId);
        putIfPresent("diagnosisRunId", runId);
        response.setHeader("X-Trace-Id", traceId);
        try {
            filterChain.doFilter(request, response);
        } finally {
            MDC.remove("traceId");
            MDC.remove("sessionId");
            MDC.remove("diagnosisRunId");
        }
    }

    private void putIfPresent(String key, String value) {
        if (value != null) {
            MDC.put(key, value);
        }
    }

    private String extractRunId(String requestUri) {
        if (requestUri == null) {
            return null;
        }
        var matcher = RUN_PATH_PATTERN.matcher(requestUri);
        return matcher.matches() ? matcher.group(1) : null;
    }

    private String normalize(String value, int maxLength) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = value.strip();
        return normalized.length() <= maxLength ? normalized : normalized.substring(0, maxLength);
    }
}
