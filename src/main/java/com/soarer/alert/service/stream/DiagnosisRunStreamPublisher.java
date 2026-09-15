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
 * 诊断任务 Redis Stream 发布器。
 */
@Service
public class DiagnosisRunStreamPublisher {

    private final StringRedisTemplate redisTemplate;

    @Value("${app.diagnosis-stream.stream-key:soarer:diagnosis:stream}")
    private String streamKey;

    @Value("${app.diagnosis-stream.dead-letter-key:soarer:diagnosis:dead-letter}")
    private String deadLetterKey;

    public DiagnosisRunStreamPublisher(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    public RecordId enqueue(UUID diagnosisRunId) {
        return enqueue(diagnosisRunId, 1);
    }

    public RecordId enqueue(UUID diagnosisRunId, int attempt) {
        Map<String, String> fields = new HashMap<>();
        fields.put("diagnosisRunId", diagnosisRunId.toString());
        fields.put("attempt", Integer.toString(attempt));
        return redisTemplate.opsForStream().add(streamKey, fields);
    }

    public RecordId enqueueDeadLetter(UUID diagnosisRunId, int attempt, String failureReason) {
        Map<String, String> fields = new HashMap<>();
        fields.put("diagnosisRunId", diagnosisRunId == null ? "unknown" : diagnosisRunId.toString());
        fields.put("attempt", Integer.toString(Math.max(attempt, 1)));
        fields.put("failedAt", Instant.now().toString());
        fields.put("failureReason", failureReason == null ? "unknown" : failureReason);
        return redisTemplate.opsForStream().add(deadLetterKey, fields);
    }
}
