package com.soarer.alert.dto.ops;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * AlertView 数据传输对象。
 */
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
