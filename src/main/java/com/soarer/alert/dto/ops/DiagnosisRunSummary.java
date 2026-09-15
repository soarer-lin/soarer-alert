package com.soarer.alert.dto.ops;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * DiagnosisRunSummary 数据传输对象。
 */
public record DiagnosisRunSummary(
        UUID id,
        String requestText,
        String status,
        LocalDateTime startedAt,
        LocalDateTime completedAt,
        Long durationMs,
        String errorMessage
) {
}
