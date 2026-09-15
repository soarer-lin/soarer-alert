package com.soarer.alert.service.stream;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 文档索引 Redis Stream 发布器。
 */
@Service
public class DocumentIndexStreamPublisher {

    private final StringRedisTemplate redisTemplate;

    @Value("${app.document-index.stream-key:soarer:document-index:stream}")
    private String streamKey;

    @Value("${app.document-index.dead-letter-key:soarer:document-index:dead-letter}")
    private String deadLetterKey;

    public DocumentIndexStreamPublisher(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    public RecordId enqueue(UUID documentId, String contentHash) {
        return enqueue(documentId, contentHash, 1);
    }

    public RecordId enqueue(UUID documentId, String contentHash, int attempt) {
        Map<String, String> fields = new HashMap<>();
        fields.put("documentId", documentId.toString());
        fields.put("contentHash", contentHash);
        fields.put("attempt", Integer.toString(attempt));
        return redisTemplate.opsForStream().add(streamKey, fields);
    }

    public RecordId enqueueDeadLetter(
            UUID documentId,
            String contentHash,
            int attempt,
            String failureReason
    ) {
        Map<String, String> fields = new HashMap<>();
        fields.put("documentId", documentId == null ? "unknown" : documentId.toString());
        fields.put("contentHash", contentHash == null ? "unknown" : contentHash);
        fields.put("attempt", Integer.toString(Math.max(attempt, 1)));
        fields.put("failedAt", Instant.now().toString());
        fields.put("failureReason", failureReason == null ? "unknown" : failureReason);
        return redisTemplate.opsForStream().add(deadLetterKey, fields);
    }
}
