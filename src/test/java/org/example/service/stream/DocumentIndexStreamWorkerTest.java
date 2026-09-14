package org.example.service.stream;

import org.example.dto.DocumentChunk;
import org.example.dto.ParsedDocument;
import org.example.entity.OpsDocument;
import org.example.service.DocumentChunkService;
import org.example.service.VectorIndexService;
import org.example.service.document.DocumentParseService;
import org.example.service.observability.OpsMetricsService;
import org.example.service.persistence.DocumentPersistenceService;
import org.example.service.storage.ObjectStorageService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisSystemException;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.connection.stream.StreamReadOptions;
import org.springframework.data.redis.core.StreamOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DocumentIndexStreamWorkerTest {

    private static final String STREAM_KEY = "soarer:document-index:stream";
    private static final String GROUP = "document-index-workers";
    private static final String CONTENT_HASH = "hash-1";
    private static final String STORAGE_KEY = "documents/runbook.md";
    private static final UUID DOCUMENT_ID = UUID.randomUUID();

    private final StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
    private final DocumentIndexStreamPublisher publisher = mock(DocumentIndexStreamPublisher.class);
    private final DocumentPersistenceService persistenceService = mock(DocumentPersistenceService.class);
    private final ObjectStorageService objectStorageService = mock(ObjectStorageService.class);
    private final DocumentParseService parseService = mock(DocumentParseService.class);
    private final DocumentChunkService chunkService = mock(DocumentChunkService.class);
    private final VectorIndexService vectorIndexService = mock(VectorIndexService.class);
    private final OpsMetricsService metricsService =
            new OpsMetricsService(new SimpleMeterRegistry());
    @SuppressWarnings("unchecked")
    private final StreamOperations<String, String, String> streamOperations =
            mock(StreamOperations.class);

    private DocumentIndexStreamWorker worker;

    @BeforeEach
    void setUp() {
        worker = new DocumentIndexStreamWorker(
                redisTemplate,
                publisher,
                persistenceService,
                objectStorageService,
                parseService,
                chunkService,
                vectorIndexService,
                metricsService
        );
        ReflectionTestUtils.setField(worker, "streamKey", STREAM_KEY);
        ReflectionTestUtils.setField(worker, "group", GROUP);
        ReflectionTestUtils.setField(worker, "consumerName", "test-worker");
        ReflectionTestUtils.setField(worker, "maxAttempts", 3);
        ReflectionTestUtils.setField(worker, "batchSize", 10);
        ReflectionTestUtils.setField(worker, "pollTimeout", Duration.ofSeconds(1));
        ReflectionTestUtils.setField(worker, "claimTimeout", Duration.ofMinutes(2));
        doReturn(streamOperations).when(redisTemplate).opsForStream();
    }

    @Test
    void continuesPollingWhenRedisWrapsBusyGroupInCauseChain() {
        when(streamOperations.createGroup(STREAM_KEY, ReadOffset.from("0"), GROUP))
                .thenThrow(new RedisSystemException(
                        "Error in execution",
                        new RuntimeException("BUSYGROUP Consumer Group name already exists")
                ));

        worker.pollNewTasks();

        verify(streamOperations).read(
                any(Consumer.class),
                any(StreamReadOptions.class),
                any(StreamOffset.class)
        );
    }

    @Test
    void processesTaskAndAcknowledgesRecord() {
        OpsDocument document = document(DocumentPersistenceService.STATUS_QUEUED);
        ParsedDocument parsed = new ParsedDocument("runbook.md", "text/markdown", "runbook", 11, false);
        DocumentChunk chunk = new DocumentChunk("runbook", 0, 7, 0);
        MapRecord<String, String, String> record = taskRecord(1);

        when(persistenceService.getDocument(DOCUMENT_ID)).thenReturn(document);
        when(objectStorageService.downloadObject(STORAGE_KEY)).thenReturn("runbook".getBytes());
        when(parseService.parse(any(byte[].class), eq("runbook.md"))).thenReturn(parsed);
        when(chunkService.chunkDocument("runbook", STORAGE_KEY)).thenReturn(List.of(chunk));

        worker.processRecord(record);

        verify(persistenceService).markIndexing(DOCUMENT_ID);
        verify(vectorIndexService).indexDocument(parsed, STORAGE_KEY, List.of(chunk));
        verify(persistenceService).markIndexingSuccess(DOCUMENT_ID, List.of(chunk));
        verify(streamOperations).acknowledge(STREAM_KEY, GROUP, record.getId());
        verify(publisher, never()).enqueue(any(UUID.class), anyString(), any(Integer.class));
        verify(publisher, never()).enqueueDeadLetter(any(), anyString(), any(Integer.class), anyString());
    }

    @Test
    void retriesFailedTaskWithNextAttempt() {
        OpsDocument document = document(DocumentPersistenceService.STATUS_QUEUED);
        MapRecord<String, String, String> record = taskRecord(1);

        when(persistenceService.getDocument(DOCUMENT_ID)).thenReturn(document);
        when(objectStorageService.downloadObject(STORAGE_KEY)).thenThrow(new RuntimeException("download failed"));

        worker.processRecord(record);

        verify(publisher).enqueue(DOCUMENT_ID, CONTENT_HASH, 2);
        verify(persistenceService).markIndexingRetrying(eq(DOCUMENT_ID), eq(2), eq("download failed"));
        verify(streamOperations).acknowledge(STREAM_KEY, GROUP, record.getId());
        verify(publisher, never()).enqueueDeadLetter(any(), anyString(), any(Integer.class), anyString());
    }

    @Test
    void sendsTaskToDeadLetterAfterMaxAttempts() {
        OpsDocument document = document(DocumentPersistenceService.STATUS_RETRYING);
        MapRecord<String, String, String> record = taskRecord(3);

        when(persistenceService.getDocument(DOCUMENT_ID)).thenReturn(document);
        when(objectStorageService.downloadObject(STORAGE_KEY)).thenThrow(new RuntimeException("download failed"));

        worker.processRecord(record);

        verify(publisher).enqueueDeadLetter(DOCUMENT_ID, CONTENT_HASH, 3, "download failed");
        verify(persistenceService).markIndexingFailed(DOCUMENT_ID, "download failed");
        verify(streamOperations).acknowledge(STREAM_KEY, GROUP, record.getId());
        verify(publisher, never()).enqueue(any(UUID.class), anyString(), any(Integer.class));
    }

    @Test
    void skipsAlreadySuccessfulDocumentWithoutIndexingAgain() {
        OpsDocument document = document(DocumentPersistenceService.STATUS_SUCCESS);
        MapRecord<String, String, String> record = taskRecord(1);

        when(persistenceService.getDocument(DOCUMENT_ID)).thenReturn(document);

        worker.processRecord(record);

        verify(persistenceService, never()).markIndexing(DOCUMENT_ID);
        verify(objectStorageService, never()).downloadObject(anyString());
        verify(parseService, never()).parse(any(), anyString());
        verify(chunkService, never()).chunkDocument(anyString(), anyString());
        verify(vectorIndexService, never()).indexDocument(any(), anyString(), any());
        verify(persistenceService, never()).markIndexingSuccess(any(UUID.class), any());
        verify(streamOperations).acknowledge(STREAM_KEY, GROUP, record.getId());
    }

    @Test
    void rejectsTaskWhenContentHashDoesNotMatchDocument() {
        OpsDocument document = document(DocumentPersistenceService.STATUS_QUEUED);
        document.setContentHash("different-hash");
        MapRecord<String, String, String> record = taskRecord(1);

        when(persistenceService.getDocument(DOCUMENT_ID)).thenReturn(document);

        worker.processRecord(record);

        verify(publisher).enqueueDeadLetter(DOCUMENT_ID, CONTENT_HASH, 1, "文档 Hash 与任务不一致");
        verify(persistenceService).markIndexingFailed(DOCUMENT_ID, "文档 Hash 与任务不一致");
        verify(persistenceService, never()).markIndexing(DOCUMENT_ID);
        verify(objectStorageService, never()).downloadObject(anyString());
        verify(streamOperations).acknowledge(STREAM_KEY, GROUP, record.getId());
    }

    @Test
    void sendsInvalidMessageToDeadLetterAndAcknowledgesIt() {
        MapRecord<String, String, String> record =
                MapRecord.<String, String, String>create(STREAM_KEY, Map.of())
                .withId(RecordId.of("1-1"));

        worker.processRecord(record);

        verify(publisher).enqueueDeadLetter(
                eq(null),
                eq(null),
                eq(1),
                argThat(reason -> reason != null && reason.startsWith("Invalid message:"))
        );
        verify(persistenceService, never()).getDocument(any(UUID.class));
        verify(streamOperations).acknowledge(STREAM_KEY, GROUP, record.getId());
    }

    private MapRecord<String, String, String> taskRecord(int attempt) {
        Map<String, String> fields = new HashMap<>();
        fields.put("documentId", DOCUMENT_ID.toString());
        fields.put("contentHash", CONTENT_HASH);
        fields.put("attempt", Integer.toString(attempt));
        return MapRecord.<String, String, String>create(STREAM_KEY, fields)
                .withId(RecordId.of("1-1"));
    }

    private OpsDocument document(String status) {
        OpsDocument document = new OpsDocument();
        document.setId(DOCUMENT_ID);
        document.setFileName("runbook.md");
        document.setStorageKey(STORAGE_KEY);
        document.setContentHash(CONTENT_HASH);
        document.setMediaType("text/markdown");
        document.setByteSize(11L);
        document.setStatus(status);
        document.setDocumentVersion(1);
        return document;
    }
}
