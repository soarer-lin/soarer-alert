package org.example.dto.ops;

import java.time.LocalDateTime;
import java.util.UUID;

public record ToolInvocationView(
        UUID id,
        UUID diagnosisRunId,
        UUID agentStepId,
        String toolName,
        String status,
        LocalDateTime startedAt,
        LocalDateTime completedAt,
        Long durationMs,
        String errorMessage
) {
}
