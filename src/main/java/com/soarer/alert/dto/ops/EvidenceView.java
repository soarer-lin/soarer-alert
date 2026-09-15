package com.soarer.alert.dto.ops;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * EvidenceView 数据传输对象。
 */
public record EvidenceView(
        UUID id,
        UUID diagnosisRunId,
        String evidenceType,
        String source,
        String content,
        LocalDateTime createdAt
) {
}
