package com.soarer.alert.observability;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import com.soarer.alert.entity.OpsToolInvocation;
import com.soarer.alert.repository.OpsToolInvocationRepository;
import com.soarer.alert.service.observability.OpsMetricsService;
import com.soarer.alert.service.persistence.DiagnosisPersistenceService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.MDC;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class InstrumentedToolCallbackTest {

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final OpsMetricsService metricsService = new OpsMetricsService(registry);
    private final OpsToolInvocationRepository repository = mock(OpsToolInvocationRepository.class);
    private final ToolInvocationAuditService auditService =
            new ToolInvocationAuditService(metricsService, repository);

    @AfterEach
    void tearDown() {
        MDC.clear();
    }

    @Test
    void recordsExternalToolCallAndPersistsInvocation() {
        UUID runId = UUID.randomUUID();
        MDC.put("diagnosisRunId", runId.toString());
        when(repository.save(any(OpsToolInvocation.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        InstrumentedToolCallback callback = new InstrumentedToolCallback(
                new SearchLogCallback(),
                auditService
        );

        String result = callback.call("{\"query\":\"level:ERROR\"}");

        assertThat(result).isEqualTo("{\"success\":true,\"logs\":[]}");
        assertThat(registry.get("soarer.aiops.tool.calls")
                .tag("tool", "SearchLog")
                .tag("outcome", DiagnosisPersistenceService.STATUS_SUCCESS)
                .counter()
                .count()).isEqualTo(1.0);

        ArgumentCaptor<OpsToolInvocation> invocationCaptor =
                ArgumentCaptor.forClass(OpsToolInvocation.class);
        verify(repository, times(2)).save(invocationCaptor.capture());
        List<OpsToolInvocation> invocations = invocationCaptor.getAllValues();
        assertThat(invocations.get(0).getToolName()).isEqualTo("SearchLog");
        assertThat(invocations.get(1).getStatus()).isEqualTo(DiagnosisPersistenceService.STATUS_SUCCESS);
        assertThat(invocations.get(1).getDiagnosisRunId()).isEqualTo(runId);
    }

    @Test
    void recordsExternalToolFailure() {
        MDC.put("diagnosisRunId", UUID.randomUUID().toString());
        when(repository.save(any(OpsToolInvocation.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        InstrumentedToolCallback callback = new InstrumentedToolCallback(
                new FailingCallback(),
                auditService
        );

        assertThatThrownBy(() -> callback.call("{}"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("CLS unavailable");

        assertThat(registry.get("soarer.aiops.tool.calls")
                .tag("tool", "SearchLog")
                .tag("outcome", DiagnosisPersistenceService.STATUS_FAILED)
                .counter()
                .count()).isEqualTo(1.0);
    }

    private static ToolDefinition toolDefinition() {
        return new ToolDefinition() {
            @Override
            public String name() {
                return "SearchLog";
            }

            @Override
            public String description() {
                return "Search Tencent Cloud CLS logs";
            }

            @Override
            public String inputSchema() {
                return "{}";
            }
        };
    }

    private static class SearchLogCallback implements ToolCallback {
        @Override
        public ToolDefinition getToolDefinition() {
            return toolDefinition();
        }

        @Override
        public String call(String toolInput) {
            return "{\"success\":true,\"logs\":[]}";
        }
    }

    private static class FailingCallback implements ToolCallback {
        @Override
        public ToolDefinition getToolDefinition() {
            return toolDefinition();
        }

        @Override
        public String call(String toolInput) {
            throw new IllegalStateException("CLS unavailable");
        }
    }
}
