package com.soarer.alert.dto.ops;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * AgentStepView 数据传输对象。
 */
public record AgentStepView(
        UUID id,
        int stepIndex,
        String agentName,
        String stepType,
        String instruction,
        String inputText,
        String outputText,
        String status,
        LocalDateTime startedAt,
        LocalDateTime completedAt,
        Long durationMs,
        String errorMessage
) {
}
