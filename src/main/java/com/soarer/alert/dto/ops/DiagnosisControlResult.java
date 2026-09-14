package com.soarer.alert.dto.ops;

import java.time.LocalDateTime;
import java.util.UUID;

public record DiagnosisControlResult(
        UUID runId,
        String command,
        String result,
        String status,
        String message,
        LocalDateTime timestamp
) {

    public static DiagnosisControlResult accepted(UUID runId, String command, String status) {
        return new DiagnosisControlResult(
                runId,
                command,
                "ACCEPTED",
                status,
                null,
                LocalDateTime.now()
        );
    }

    public static DiagnosisControlResult rejected(
            UUID runId,
            String command,
            String status,
            String message
    ) {
        return new DiagnosisControlResult(
                runId,
                command,
                "REJECTED",
                status,
                message,
                LocalDateTime.now()
        );
    }

    public static DiagnosisControlResult unsupported(UUID runId, String command, String status) {
        return new DiagnosisControlResult(
                runId,
                command,
                "UNSUPPORTED",
                status,
                "当前编排尚未接入安全检查点，pause/resume 暂不开放",
                LocalDateTime.now()
        );
    }
}
