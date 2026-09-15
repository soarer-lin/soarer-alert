package com.soarer.alert.dto.ops;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * DocumentView 数据传输对象。
 */
public record DocumentView(
        UUID id,
        String fileName,
        String storageKey,
        String storageUrl,
        String contentHash,
        String mediaType,
        long byteSize,
        String status,
        int documentVersion,
        String failureReason,
        LocalDateTime createdAt,
        LocalDateTime updatedAt,
        LocalDateTime indexedAt,
        UUID createdBy,
        boolean canDelete
) {
}
