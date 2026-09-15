package com.soarer.alert.observability;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import com.soarer.alert.service.observability.OpsMetricsService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Redis Stream 指标绑定器。
 */
@Component
public class RedisStreamMetricsBinder implements MeterBinder {

    private final StringRedisTemplate redisTemplate;

    @Value("${app.diagnosis-stream.stream-key:soarer:diagnosis:stream}")
    private String diagnosisStreamKey;

    @Value("${app.diagnosis-stream.group:diagnosis-workers}")
    private String diagnosisGroup;

    @Value("${app.diagnosis-stream.dead-letter-key:soarer:diagnosis:dead-letter}")
    private String diagnosisDeadLetterKey;

    @Value("${app.document-index.stream-key:soarer:document-index:stream}")
    private String documentStreamKey;

    @Value("${app.document-index.group:document-index-workers}")
    private String documentGroup;

    @Value("${app.document-index.dead-letter-key:soarer:document-index:dead-letter}")
    private String documentDeadLetterKey;

    public RedisStreamMetricsBinder(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    @Override
    public void bindTo(MeterRegistry registry) {
        bindStream(registry, "diagnosis", diagnosisStreamKey, diagnosisGroup, diagnosisDeadLetterKey);
        bindStream(registry, "document-index", documentStreamKey, documentGroup, documentDeadLetterKey);
    }

    private void bindStream(
            MeterRegistry registry,
            String streamName,
            String streamKey,
            String group,
            String deadLetterKey
    ) {
        Gauge.builder("soarer.redis.stream.pending", this,
                        value -> pendingCount(streamKey, group))
                .tag("stream", streamName)
                .description("Number of pending Redis Stream messages for the consumer group.")
                .register(registry);
        Gauge.builder("soarer.redis.stream.lag", this,
                        value -> streamLag(streamKey, group))
                .tag("stream", streamName)
                .description("Redis Stream consumer-group lag reported by Redis 7 XINFO.")
                .register(registry);
        Gauge.builder("soarer.redis.stream.dead-letter.size", this,
                        value -> deadLetterSize(deadLetterKey))
                .tag("stream", streamName)
                .description("Number of records in the Redis Stream dead-letter stream.")
                .register(registry);
    }

    private double pendingCount(String streamKey, String group) {
        try {
            return redisTemplate.opsForStream().groups(streamKey)
                    .stream()
                    .filter(groupInfo -> group.equals(groupInfo.groupName()))
                    .findFirst()
                    .map(groupInfo -> groupInfo.pendingCount() == null
                            ? Double.NaN
                            : groupInfo.pendingCount().doubleValue())
                    .orElse(Double.NaN);
        } catch (Exception e) {
            return Double.NaN;
        }
    }

    private double streamLag(String streamKey, String group) {
        try {
            return redisTemplate.opsForStream().groups(streamKey)
                    .stream()
                    .filter(groupInfo -> group.equals(groupInfo.groupName()))
                    .findFirst()
                    .map(groupInfo -> {
                        Object lag = groupInfo.getRaw().get("lag");
                        return toDouble(lag);
                    })
                    .orElse(Double.NaN);
        } catch (Exception e) {
            return Double.NaN;
        }
    }

    private double deadLetterSize(String deadLetterKey) {
        try {
            Long size = redisTemplate.opsForStream().size(deadLetterKey);
            return size == null ? Double.NaN : size.doubleValue();
        } catch (Exception e) {
            return Double.NaN;
        }
    }

    private double toDouble(Object value) {
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        if (value instanceof CharSequence text) {
            try {
                return Double.parseDouble(text.toString());
            } catch (NumberFormatException ignored) {
                return Double.NaN;
            }
        }
        return Double.NaN;
    }
}
