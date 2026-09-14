package org.example.observability;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.entity.OpsToolInvocation;
import org.example.repository.OpsToolInvocationRepository;
import org.example.service.observability.OpsMetricsService;
import org.example.service.persistence.DiagnosisPersistenceService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Records tool calls for both local @Tool methods and external MCP callbacks.
 */
@Service
public class ToolInvocationAuditService {

    private static final Logger logger = LoggerFactory.getLogger(ToolInvocationAuditService.class);
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final OpsMetricsService metricsService;
    private final OpsToolInvocationRepository toolInvocationRepository;

    public ToolInvocationAuditService(
            OpsMetricsService metricsService,
            OpsToolInvocationRepository toolInvocationRepository
    ) {
        this.metricsService = metricsService;
        this.toolInvocationRepository = toolInvocationRepository;
    }

    public StartedTool start(String toolName) {
        return new StartedTool(
                OpsMetricsService.safeTag(toolName),
                metricsService.startClock(),
                createInvocation(toolName)
        );
    }

    public void success(StartedTool startedTool, String result) {
        complete(startedTool, resolveOutcome(result), null);
    }

    public void failure(StartedTool startedTool, String errorMessage) {
        complete(startedTool, DiagnosisPersistenceService.STATUS_FAILED, errorMessage);
    }

    private OpsToolInvocation createInvocation(String toolName) {
        String runId = MDC.get("diagnosisRunId");
        if (runId == null || runId.isBlank()) {
            return null;
        }
        try {
            OpsToolInvocation invocation = new OpsToolInvocation();
            invocation.setDiagnosisRunId(UUID.fromString(runId));
            String stepId = MDC.get("agentStepId");
            if (stepId != null && !stepId.isBlank()) {
                invocation.setAgentStepId(UUID.fromString(stepId));
            }
            invocation.setToolName(OpsMetricsService.safeTag(toolName));
            invocation.setStatus(DiagnosisPersistenceService.STATUS_RUNNING);
            invocation.setStartedAt(LocalDateTime.now());
            return toolInvocationRepository.save(invocation);
        } catch (IllegalArgumentException e) {
            logger.debug("Ignoring invalid diagnosis run ID in tool invocation MDC: {}", runId);
            return null;
        } catch (Exception e) {
            logger.warn("Could not persist tool invocation for {}: {}", toolName, e.getMessage());
            return null;
        }
    }

    private void complete(StartedTool startedTool, String status, String errorMessage) {
        if (startedTool == null) {
            return;
        }
        if (startedTool.invocation() != null) {
            try {
                startedTool.invocation().complete(status);
                if (errorMessage != null && !errorMessage.isBlank()) {
                    startedTool.invocation().setErrorMessage(errorMessage);
                }
                toolInvocationRepository.save(startedTool.invocation());
            } catch (Exception e) {
                logger.warn("Could not complete tool invocation persistence: {}", e.getMessage());
            }
        }
        metricsService.recordToolInvocation(startedTool.startedNanos(), startedTool.toolName(), status);
    }

    private String resolveOutcome(String result) {
        if (result == null || result.isBlank()) {
            return DiagnosisPersistenceService.STATUS_SUCCESS;
        }
        try {
            JsonNode root = OBJECT_MAPPER.readTree(result);
            JsonNode success = root.get("success");
            if (success != null && !success.asBoolean(true)) {
                return DiagnosisPersistenceService.STATUS_FAILED;
            }
            JsonNode status = root.get("status");
            if (status != null && "error".equalsIgnoreCase(status.asText())) {
                return DiagnosisPersistenceService.STATUS_FAILED;
            }
        } catch (Exception ignored) {
            // Tool results are diagnostic strings; non-JSON output is still a successful tool call.
        }
        return DiagnosisPersistenceService.STATUS_SUCCESS;
    }

    public record StartedTool(String toolName, long startedNanos, OpsToolInvocation invocation) {
    }
}
