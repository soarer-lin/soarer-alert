package org.example.dto.ops;

import java.time.LocalDateTime;
import java.util.UUID;

public record ReportView(
        UUID id,
        UUID diagnosisRunId,
        String content,
        String reportUrl,
        String status,
        LocalDateTime createdAt
) {
}
