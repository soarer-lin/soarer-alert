package com.soarer.alert.observability;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.agent.hook.AgentHook;
import com.soarer.alert.entity.OpsAgentStep;
import com.soarer.alert.service.persistence.DiagnosisPersistenceService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Persists the actual lifecycle of a ReactAgent invocation.
 *
 * <p>Each ReactAgent gets its own hook instance because the framework assigns
 * the owning agent name to the hook while compiling the graph.</p>
 */
public class DiagnosisAgentLifecycleHook extends AgentHook {

    private static final Logger logger = LoggerFactory.getLogger(DiagnosisAgentLifecycleHook.class);
    private static final String CONTEXT_RUN_ID = "soarer.diagnosis.runId";
    private static final String CONTEXT_REQUEST_TEXT = "soarer.diagnosis.requestText";
    private static final ThreadLocal<Deque<StepFrame>> STEP_STACK =
            ThreadLocal.withInitial(ArrayDeque::new);

    private final DiagnosisPersistenceService persistenceService;

    public DiagnosisAgentLifecycleHook(DiagnosisPersistenceService persistenceService) {
        this.persistenceService = persistenceService;
    }

    public static void putExecutionContext(RunnableConfig config, UUID diagnosisRunId, String requestText) {
        if (config == null || diagnosisRunId == null) {
            return;
        }
        config.context().put(CONTEXT_RUN_ID, diagnosisRunId.toString());
        if (requestText != null && !requestText.isBlank()) {
            config.context().put(CONTEXT_REQUEST_TEXT, requestText);
        }
    }

    public static void clearThreadState() {
        STEP_STACK.remove();
    }

    @Override
    public String getName() {
        return "diagnosis_agent_lifecycle";
    }

    @Override
    public CompletableFuture<Map<String, Object>> beforeAgent(
            OverAllState state,
            RunnableConfig config
    ) {
        UUID runId = resolveRunId(config);
        if (runId == null) {
            return CompletableFuture.completedFuture(Map.of());
        }

        String agentName = getAgentName();
        if (agentName == null || agentName.isBlank()) {
            return CompletableFuture.completedFuture(Map.of());
        }

        try {
            StepFrame parent = STEP_STACK.get().peek();
            String previousStepId = MDC.get("agentStepId");
            OpsAgentStep step = persistenceService.startStep(
                    runId,
                    agentName,
                    stepType(agentName),
                    instruction(agentName),
                    resolveInputText(state, config)
            );
            STEP_STACK.get().push(new StepFrame(runId, step.getId(), previousStepId));
            MDC.put("diagnosisRunId", runId.toString());
            MDC.put("agentStepId", step.getId().toString());
            logger.debug("Started diagnosis Agent step {} for run {}", agentName, runId);
        } catch (Exception failure) {
            logger.warn("Could not start diagnosis Agent step {}: {}", agentName, failure.getMessage());
        }
        return CompletableFuture.completedFuture(Map.of());
    }

    @Override
    public CompletableFuture<Map<String, Object>> afterAgent(
            OverAllState state,
            RunnableConfig config
    ) {
        UUID runId = resolveRunId(config);
        if (runId == null) {
            return CompletableFuture.completedFuture(Map.of());
        }

        StepFrame frame = popFrame(runId);
        if (frame == null) {
            return CompletableFuture.completedFuture(Map.of());
        }

        try {
            persistenceService.completeStep(
                    frame.stepId(),
                    resolveOutputText(state, getAgentName())
            );
            logger.debug("Completed diagnosis Agent step {} for run {}", getAgentName(), runId);
        } catch (Exception failure) {
            logger.warn("Could not complete diagnosis Agent step {}: {}", getAgentName(), failure.getMessage());
        } finally {
            restoreStepContext(frame.previousStepId());
        }
        return CompletableFuture.completedFuture(Map.of());
    }

    private StepFrame popFrame(UUID runId) {
        Deque<StepFrame> stack = STEP_STACK.get();
        StepFrame frame = stack.peek();
        if (frame == null || !runId.equals(frame.runId())) {
            return null;
        }
        stack.pop();
        if (stack.isEmpty()) {
            STEP_STACK.remove();
        }
        return frame;
    }

    private void restoreStepContext(String previousStepId) {
        if (previousStepId == null || previousStepId.isBlank()) {
            MDC.remove("agentStepId");
        } else {
            MDC.put("agentStepId", previousStepId);
        }
    }

    private UUID resolveRunId(RunnableConfig config) {
        Object contextValue = config == null ? null : config.context().get(CONTEXT_RUN_ID);
        String runId = contextValue == null ? MDC.get("diagnosisRunId") : String.valueOf(contextValue);
        if (runId == null || runId.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(runId);
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private String resolveInputText(OverAllState state, RunnableConfig config) {
        if (state != null) {
            Optional<Object> input = state.value("input");
            if (input.isPresent() && input.get() != null) {
                return String.valueOf(input.get());
            }
        }
        Object requestText = config == null ? null : config.context().get(CONTEXT_REQUEST_TEXT);
        if (requestText != null) {
            return String.valueOf(requestText);
        }
        return latestUserMessage(state).orElse(null);
    }

    private String resolveOutputText(OverAllState state, String agentName) {
        if (state == null) {
            return null;
        }
        String outputKey = switch (agentName) {
            case "planner_agent" -> "planner_plan";
            case "executor_agent" -> "executor_feedback";
            default -> null;
        };
        if (outputKey != null) {
            Optional<Object> output = state.value(outputKey);
            if (output.isPresent()) {
                String text = messageText(output.get());
                if (text != null && !text.isBlank()) {
                    return text;
                }
            }
        }
        return latestAssistantMessage(state).orElse(null);
    }

    private Optional<String> latestUserMessage(OverAllState state) {
        return messages(state).stream()
                .filter(UserMessage.class::isInstance)
                .map(UserMessage.class::cast)
                .map(UserMessage::getText)
                .filter(text -> text != null && !text.isBlank())
                .reduce((first, second) -> second);
    }

    private Optional<String> latestAssistantMessage(OverAllState state) {
        return messages(state).stream()
                .filter(AssistantMessage.class::isInstance)
                .map(AssistantMessage.class::cast)
                .map(AssistantMessage::getText)
                .filter(text -> text != null && !text.isBlank())
                .reduce((first, second) -> second);
    }

    private List<?> messages(OverAllState state) {
        if (state == null) {
            return List.of();
        }
        return state.value("messages")
                .filter(List.class::isInstance)
                .map(List.class::cast)
                .orElseGet(List::of);
    }

    private String messageText(Object value) {
        if (value instanceof AssistantMessage assistantMessage) {
            return assistantMessage.getText();
        }
        if (value instanceof Message message) {
            return message.getText();
        }
        return value == null ? null : String.valueOf(value);
    }

    private String stepType(String agentName) {
        return switch (agentName) {
            case "ai_ops_router" -> "ROUTING";
            case "planner_agent" -> "PLANNING";
            case "executor_agent" -> "EXECUTION";
            default -> "AGENT";
        };
    }

    private String instruction(String agentName) {
        return switch (agentName) {
            case "ai_ops_router" -> "根据 Planner 与 Executor 的最新输出决定下一个 Agent";
            case "planner_agent" -> "拆解告警、规划与再规划排查步骤";
            case "executor_agent" -> "执行 Planner 的首个步骤并整理证据反馈";
            default -> "执行诊断 Agent 步骤";
        };
    }

    private record StepFrame(UUID runId, UUID stepId, String previousStepId) {
    }
}
