package org.example.service.stream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.core.StreamOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DiagnosisRunStreamPublisherTest {

    private static final String STREAM_KEY = "soarer:diagnosis:stream";
    private static final String DEAD_LETTER_KEY = "soarer:diagnosis:dead-letter";
    private static final UUID RUN_ID = UUID.randomUUID();

    private final StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
    @SuppressWarnings("unchecked")
    private final StreamOperations<String, String, String> streamOperations =
            mock(StreamOperations.class);
    private DiagnosisRunStreamPublisher publisher;

    @BeforeEach
    void setUp() {
        publisher = new DiagnosisRunStreamPublisher(redisTemplate);
        ReflectionTestUtils.setField(publisher, "streamKey", STREAM_KEY);
        ReflectionTestUtils.setField(publisher, "deadLetterKey", DEAD_LETTER_KEY);
        doReturn(streamOperations).when(redisTemplate).opsForStream();
    }

    @Test
    void enqueuesDiagnosisRunWithAttempt() {
        when(streamOperations.add(
                eq(STREAM_KEY),
                argThat(fields -> RUN_ID.toString().equals(fields.get("diagnosisRunId"))
                        && "2".equals(fields.get("attempt")))
        )).thenReturn(RecordId.of("1-1"));

        RecordId recordId = publisher.enqueue(RUN_ID, 2);

        assertThat(recordId).isEqualTo(RecordId.of("1-1"));
    }

    @Test
    void enqueuesDeadLetterWithFailureDetails() {
        when(streamOperations.add(
                eq(DEAD_LETTER_KEY),
                argThat(fields -> RUN_ID.toString().equals(fields.get("diagnosisRunId"))
                        && "3".equals(fields.get("attempt"))
                        && fields.containsKey("failedAt")
                        && "model failed".equals(fields.get("failureReason")))
        )).thenReturn(RecordId.of("2-2"));

        RecordId recordId = publisher.enqueueDeadLetter(RUN_ID, 3, "model failed");

        assertThat(recordId).isEqualTo(RecordId.of("2-2"));
        verify(streamOperations).add(
                eq(DEAD_LETTER_KEY),
                argThat(fields -> "model failed".equals(fields.get("failureReason")))
        );
    }
}
