package org.example.observability;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.example.service.observability.OpsMetricsService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.stream.StreamInfo;
import org.springframework.data.redis.core.StreamOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RedisStreamMetricsBinderTest {

    private final StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
    @SuppressWarnings("unchecked")
    private final StreamOperations<String, Object, Object> streamOperations =
            mock(StreamOperations.class);
    private final RedisStreamMetricsBinder binder = new RedisStreamMetricsBinder(redisTemplate);
    private final MeterRegistry registry = new SimpleMeterRegistry();

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(binder, "diagnosisStreamKey", "diagnosis-stream");
        ReflectionTestUtils.setField(binder, "diagnosisGroup", "diagnosis-workers");
        ReflectionTestUtils.setField(binder, "diagnosisDeadLetterKey", "diagnosis-dead-letter");
        ReflectionTestUtils.setField(binder, "documentStreamKey", "document-stream");
        ReflectionTestUtils.setField(binder, "documentGroup", "document-workers");
        ReflectionTestUtils.setField(binder, "documentDeadLetterKey", "document-dead-letter");
        when(redisTemplate.opsForStream()).thenReturn(streamOperations);
    }

    @Test
    void bindsPendingLagAndDeadLetterGauges() {
        StreamInfo.XInfoGroups groups = mock(StreamInfo.XInfoGroups.class);
        StreamInfo.XInfoGroup group = StreamInfo.XInfoGroup.fromList(List.of(
                "name", "diagnosis-workers",
                "pending", 3L,
                "lag", 7
        ));
        when(groups.stream()).thenAnswer(invocation -> List.of(group).stream());
        when(streamOperations.groups("diagnosis-stream")).thenReturn(groups);
        when(streamOperations.size("diagnosis-dead-letter")).thenReturn(2L);

        binder.bindTo(registry);

        assertThat(registry.get("soarer.redis.stream.pending")
                .tag("stream", "diagnosis")
                .gauge()
                .value()).isEqualTo(3.0);
        assertThat(registry.get("soarer.redis.stream.lag")
                .tag("stream", "diagnosis")
                .gauge()
                .value()).isEqualTo(7.0);
        assertThat(registry.get("soarer.redis.stream.dead-letter.size")
                .tag("stream", "diagnosis")
                .gauge()
                .value()).isEqualTo(2.0);
    }
}
