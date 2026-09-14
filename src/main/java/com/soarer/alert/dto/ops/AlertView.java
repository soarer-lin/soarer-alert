package com.soarer.alert.dto.ops;

import java.time.LocalDateTime;
import java.util.UUID;

public record AlertView(
        UUID id,
        UUID diagnosisRunId,
        String alertName,
        String severity,
        String serviceName,
        String environment,
        String status,
        LocalDateTime firstTriggeredAt,
        LocalDateTime lastTriggeredAt,
        LocalDateTime createdAt
) {
}
