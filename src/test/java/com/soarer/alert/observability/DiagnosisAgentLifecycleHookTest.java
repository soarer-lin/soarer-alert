package com.soarer.alert.observability;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.soarer.alert.entity.OpsAgentStep;
import com.soarer.alert.service.persistence.DiagnosisPersistenceService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.slf4j.MDC;

import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.assertj.core.api.Assertions.assertThat;

class DiagnosisAgentLifecycleHookTest {

    private final DiagnosisPersistenceService persistenceService =
            mock(DiagnosisPersistenceService.class);

    @AfterEach
    void tearDown() {
        DiagnosisAgentLifecycleHook.clearThreadState();
        MDC.clear();
    }

    @Test
    void persistsAgentLifecycleAndRestoresParentStepContext() {
        UUID runId = UUID.randomUUID();
        UUID parentStepId = UUID.randomUUID();
        OpsAgentStep step = step(UUID.randomUUID(), runId, "planner_agent", "PLANNING");
        when(persistenceService.startStep(
                eq(runId),
                eq("planner_agent"),
                eq("PLANNING"),
                any(String.class),
                any(String.class)
        )).thenReturn(step);

        OverAllState state = mock(OverAllState.class);
        when(state.value("input")).thenReturn(Optional.of("diagnose CPU"));
        when(state.value("planner_plan"))
                .thenReturn(Optional.of(new AssistantMessage("planner output")));

        RunnableConfig config = RunnableConfig.builder().build();
        DiagnosisAgentLifecycleHook.putExecutionContext(config, runId, "diagnosis request");
        MDC.put("agentStepId", parentStepId.toString());

        DiagnosisAgentLifecycleHook hook = hook("planner_agent");
        hook.beforeAgent(state, config).join();

        assertThat(MDC.get("diagnosisRunId")).isEqualTo(runId.toString());
        assertThat(MDC.get("agentStepId")).isEqualTo(step.getId().toString());

        hook.afterAgent(state, config).join();

        verify(persistenceService).completeStep(step.getId(), "planner output");
        assertThat(MDC.get("agentStepId")).isEqualTo(parentStepId.toString());
    }

    @Test
    void restoresNestedAgentStepInLifoOrder() {
        UUID runId = UUID.randomUUID();
        UUID parentStepId = UUID.randomUUID();
        OpsAgentStep plannerStep = step(UUID.randomUUID(), runId, "planner_agent", "PLANNING");
        OpsAgentStep executorStep = step(UUID.randomUUID(), runId, "executor_agent", "EXECUTION");
        when(persistenceService.startStep(
                eq(runId),
                eq("planner_agent"),
                eq("PLANNING"),
                any(String.class),
                any(String.class)
        )).thenReturn(plannerStep);
        when(persistenceService.startStep(
                eq(runId),
                eq("executor_agent"),
                eq("EXECUTION"),
                any(String.class),
                any(String.class)
        )).thenReturn(executorStep);

        OverAllState state = mock(OverAllState.class);
        when(state.value("input")).thenReturn(Optional.of("diagnose CPU"));
        when(state.value("planner_plan")).thenReturn(Optional.of("plan"));
        when(state.value("executor_feedback")).thenReturn(Optional.of("feedback"));

        RunnableConfig config = RunnableConfig.builder().build();
        DiagnosisAgentLifecycleHook.putExecutionContext(config, runId, "diagnosis request");
        MDC.put("agentStepId", parentStepId.toString());

        DiagnosisAgentLifecycleHook plannerHook = hook("planner_agent");
        DiagnosisAgentLifecycleHook executorHook = hook("executor_agent");
        plannerHook.beforeAgent(state, config).join();
        executorHook.beforeAgent(state, config).join();
        assertThat(MDC.get("agentStepId")).isEqualTo(executorStep.getId().toString());

        executorHook.afterAgent(state, config).join();
        assertThat(MDC.get("agentStepId")).isEqualTo(plannerStep.getId().toString());
        plannerHook.afterAgent(state, config).join();
        assertThat(MDC.get("agentStepId")).isEqualTo(parentStepId.toString());

        verify(persistenceService).completeStep(executorStep.getId(), "feedback");
        verify(persistenceService).completeStep(plannerStep.getId(), "plan");
    }

    private DiagnosisAgentLifecycleHook hook(String agentName) {
        DiagnosisAgentLifecycleHook hook = new DiagnosisAgentLifecycleHook(persistenceService);
        hook.setAgentName(agentName);
        return hook;
    }

    private OpsAgentStep step(UUID id, UUID runId, String agentName, String stepType) {
        OpsAgentStep step = new OpsAgentStep();
        step.setId(id);
        step.setDiagnosisRunId(runId);
        step.setStepIndex(1);
        step.setAgentName(agentName);
        step.setStepType(stepType);
        step.setStatus(DiagnosisPersistenceService.STATUS_RUNNING);
        return step;
    }
}
