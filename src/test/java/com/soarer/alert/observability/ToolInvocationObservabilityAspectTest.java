package com.soarer.alert.observability;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import com.soarer.alert.entity.OpsToolInvocation;
import com.soarer.alert.repository.OpsToolInvocationRepository;
import com.soarer.alert.service.observability.OpsMetricsService;
import com.soarer.alert.service.persistence.DiagnosisPersistenceService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.aop.aspectj.annotation.AspectJProxyFactory;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 验证 ToolInvocationObservabilityAspect 的行为。
 */
class ToolInvocationObservabilityAspectTest {

    private final MeterRegistry registry = new SimpleMeterRegistry();
    private final OpsMetricsService metricsService = new OpsMetricsService(registry);
    private final OpsToolInvocationRepository repository = mock(OpsToolInvocationRepository.class);
    private final ToolInvocationAuditService auditService =
            new ToolInvocationAuditService(metricsService, repository);

    @AfterEach
    void tearDown() {
        MDC.clear();
    }

    @Test
    void recordsSuccessfulToolCallAndPersistsInvocation() {
        UUID runId = UUID.randomUUID();
        UUID stepId = UUID.randomUUID();
        MDC.put("diagnosisRunId", runId.toString());
        MDC.put("agentStepId", stepId.toString());
        when(repository.save(any(OpsToolInvocation.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        SampleTool proxy = proxyFor(new SampleTool());
        String result = proxy.invoke("query");

        assertThat(result).isEqualTo("{\"success\":true}");
        assertThat(registry.get("soarer.aiops.tool.calls")
                .tag("tool", "invoke")
                .tag("outcome", DiagnosisPersistenceService.STATUS_SUCCESS)
                .counter()
                .count()).isEqualTo(1.0);
        verify(repository, times(2)).save(any(OpsToolInvocation.class));
    }

    @Test
    void marksToolErrorPayloadAsFailed() {
        MDC.put("diagnosisRunId", UUID.randomUUID().toString());
        when(repository.save(any(OpsToolInvocation.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        SampleTool proxy = proxyFor(new SampleTool());
        proxy.fail();

        assertThat(registry.get("soarer.aiops.tool.calls")
                .tag("tool", "fail")
                .tag("outcome", DiagnosisPersistenceService.STATUS_FAILED)
                .counter()
                .count()).isEqualTo(1.0);
    }

    private SampleTool proxyFor(SampleTool target) {
        AspectJProxyFactory factory = new AspectJProxyFactory(target);
        factory.addAspect(new ToolInvocationObservabilityAspect(auditService));
        return factory.getProxy();
    }

    static class SampleTool {
        @Tool(description = "Sample tool")
        public String invoke(String query) {
            return "{\"success\":true}";
        }

        @Tool(description = "Failing sample tool")
        public String fail() {
            return "{\"success\":false,\"message\":\"failed\"}";
        }
    }
}
