package com.soarer.alert.config;

import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

/**
 * 验证 ObservabilityFilter 的行为。
 */
class ObservabilityFilterTest {

    private final ObservabilityFilter filter = new ObservabilityFilter();

    @Test
    void propagatesTraceAndRunContextToMdcAndResponse() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/ai_ops/runs/" + UUID.randomUUID());
        request.addHeader("X-Trace-Id", "trace-123");
        request.addHeader("X-Session-Id", "session-123");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);
        String runId = request.getRequestURI().substring("/api/ai_ops/runs/".length());

        doAnswer(invocation -> {
            assertThat(MDC.get("traceId")).isEqualTo("trace-123");
            assertThat(MDC.get("sessionId")).isEqualTo("session-123");
            assertThat(MDC.get("diagnosisRunId")).isEqualTo(runId);
            return null;
        }).when(chain).doFilter(any(), any());

        filter.doFilter(request, response, chain);

        assertThat(response.getHeader("X-Trace-Id")).isEqualTo("trace-123");
        assertThat(MDC.get("traceId")).isNull();
        assertThat(MDC.get("sessionId")).isNull();
        assertThat(MDC.get("diagnosisRunId")).isNull();
    }

    @Test
    void generatesTraceIdWhenClientDoesNotProvideOne() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/ops.html");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(request, response, chain);

        assertThat(response.getHeader("X-Trace-Id")).hasSize(32);
    }
}
