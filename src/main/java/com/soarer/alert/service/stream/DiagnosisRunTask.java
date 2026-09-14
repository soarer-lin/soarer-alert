package com.soarer.alert.service.stream;

import java.util.Map;
import java.util.UUID;

record DiagnosisRunTask(UUID diagnosisRunId, int attempt) {

    static DiagnosisRunTask from(Map<String, String> fields) {
        if (fields == null || fields.get("diagnosisRunId") == null || fields.get("attempt") == null) {
            throw new IllegalArgumentException("Message must contain diagnosisRunId and attempt");
        }
        UUID diagnosisRunId;
        try {
            diagnosisRunId = UUID.fromString(fields.get("diagnosisRunId"));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("diagnosisRunId must be a UUID", e);
        }
        int attempt;
        try {
            attempt = Integer.parseInt(fields.get("attempt"));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("attempt must be a positive integer", e);
        }
        if (attempt < 1) {
            throw new IllegalArgumentException("attempt must be a positive integer");
        }
        return new DiagnosisRunTask(diagnosisRunId, attempt);
    }

    DiagnosisRunTask nextAttempt() {
        return new DiagnosisRunTask(diagnosisRunId, attempt + 1);
    }
}
