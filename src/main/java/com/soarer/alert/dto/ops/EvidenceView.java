package com.soarer.alert.dto.ops;

import java.time.LocalDateTime;
import java.util.UUID;

public record EvidenceView(
        UUID id,
        UUID diagnosisRunId,
        String evidenceType,
        String source,
        String content,
        LocalDateTime createdAt
) {
}
