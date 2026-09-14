package com.soarer.alert.service.stream;

import com.soarer.alert.dto.DocumentChunk;
import com.soarer.alert.dto.ParsedDocument;
import com.soarer.alert.entity.OpsDocument;
import com.soarer.alert.service.DocumentChunkService;
import com.soarer.alert.service.VectorIndexService;
import com.soarer.alert.service.document.DocumentParseService;
import com.soarer.alert.service.observability.OpsMetricsService;
import com.soarer.alert.service.persistence.DocumentPersistenceService;
import com.soarer.alert.service.storage.ObjectStorageService;
import org.slf4j.MDC;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.RedisSystemException;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.PendingMessage;
import org.springframework.data.redis.connection.stream.PendingMessages;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.connection.stream.StreamReadOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.StreamOperations;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;

@Service
@ConditionalOnProperty(
        prefix = "app.document-index",
        name = "enabled",
        havingValue = "true",
        matchIfMissing = true
)
public class DocumentIndexStreamWorker {

    private static final Logger logger = LoggerFactory.getLogger(DocumentIndexStreamWorker.class);

    private final StringRedisTemplate redisTemplate;
    private final DocumentIndexStreamPublisher publisher;
    private final DocumentPersistenceService persistenceService;
    private final ObjectStorageService objectStorageService;
    private final DocumentParseService parseService;
    private final DocumentChunkService chunkService;
    private final VectorIndexService vectorIndexService;
    private final OpsMetricsService metricsService;

    @Value("${app.document-index.stream-key:soarer:document-index:stream}")
    private String streamKey;

    @Value("${app.document-index.group:document-index-workers}")
    private String group;

    @Value("${app.document-index.consumer-name:local-document-worker-1}")
    private String consumerName;

    @Value("${app.document-index.max-attempts:3}")
    private int maxAttempts;

    @Value("${app.document-index.batch-size:10}")
    private int batchSize;

    @Value("${app.document-index.poll-timeout:PT1S}")
    private Duration pollTimeout;

    @Value("${app.document-index.claim-timeout:PT2M}")
    private Duration claimTimeout;

    public DocumentIndexStreamWorker(
            StringRedisTemplate redisTemplate,
            DocumentIndexStreamPublisher publisher,
            DocumentPersistenceService persistenceService,
            ObjectStorageService objectStorageService,
            DocumentParseService parseService,
            DocumentChunkService chunkService,
            VectorIndexService vectorIndexService,
            OpsMetricsService metricsService
    ) {
        this.redisTemplate = redisTemplate;
        this.publisher = publisher;
        this.persistenceService = persistenceService;
        this.objectStorageService = objectStorageService;
        this.parseService = parseService;
        this.chunkService = chunkService;
        this.vectorIndexService = vectorIndexService;
        this.metricsService = metricsService;
    }

    @Scheduled(fixedDelayString = "${app.document-index.poll-interval-ms:1000}")
    public void pollNewTasks() {
        if (!ensureConsumerGroup()) {
            return;
        }
        StreamOperations<String, String, String> operations = redisTemplate.opsForStream();
        List<MapRecord<String, String, String>> records = operations.read(
                Consumer.from(group, consumerName),
                StreamReadOptions.empty().count(batchSize).block(pollTimeout),
                StreamOffset.create(streamKey, ReadOffset.lastConsumed())
        );
        if (records == null || records.isEmpty()) {
            return;
        }
        records.forEach(this::processRecord);
    }

    @Scheduled(fixedDelayString = "${app.document-index.recovery-interval-ms:30000}")
    public void recoverPendingTasks() {
        if (!ensureConsumerGroup()) {
            return;
        }
        StreamOperations<String, String, String> operations = redisTemplate.opsForStream();
        PendingMessages pendingMessages = operations.pending(streamKey, group, Range.unbounded(), batchSize);
        if (pendingMessages == null || pendingMessages.isEmpty()) {
            return;
        }
        for (PendingMessage pendingMessage : pendingMessages) {
            if (pendingMessage.getElapsedTimeSinceLastDelivery().compareTo(claimTimeout) < 0) {
                continue;
            }
            List<MapRecord<String, String, String>> records = operations.claim(
                    streamKey,
                    group,
                    consumerName,
                    Duration.ZERO,
                    pendingMessage.getId()
            );
            records.forEach(this::processRecord);
        }
    }

    void processRecord(MapRecord<String, String, String> record) {
        DocumentIndexTask task = null;
        try {
            task = DocumentIndexTask.from(record.getValue());
            MDC.put("documentId", task.documentId().toString());
            MDC.put("attempt", Integer.toString(task.attempt()));
            String outcome = processTask(task);
            metricsService.recordDocumentIndexOutcome(outcome, task.attempt());
            logger.info("Document indexing completed with outcome {}: {} attempt {}",
                    outcome, task.documentId(), task.attempt());
            acknowledge(record);
        } catch (Exception e) {
            if (task == null) {
                logger.warn("Invalid document index message {}: {}", record.getId(), e.getMessage());
                publisher.enqueueDeadLetter(null, null, 1, "Invalid message: " + e.getMessage());
                metricsService.recordDocumentIndexOutcome("invalid", 1);
                acknowledge(record);
            } else {
                handleFailure(task, record, e);
            }
        } finally {
            MDC.remove("documentId");
            MDC.remove("attempt");
        }
    }

    String processTask(DocumentIndexTask task) {
        OpsDocument document = persistenceService.getDocument(task.documentId());
        if (!task.contentHash().equals(document.getContentHash())) {
            throw new NonRetryableDocumentIndexException("文档 Hash 与任务不一致");
        }
        if (DocumentPersistenceService.STATUS_SUCCESS.equals(document.getStatus())) {
            logger.info("Document already indexed, skipping task: {}", task.documentId());
            return "skipped";
        }
        if (document.getStorageKey() == null || document.getStorageKey().isBlank()) {
            throw new NonRetryableDocumentIndexException("文档缺少对象存储 Key，无法异步索引");
        }

        persistenceService.markIndexing(task.documentId());
        byte[] bytes = objectStorageService.downloadObject(document.getStorageKey());
        ParsedDocument parsedDocument = parseService.parse(bytes, document.getFileName());
        parsedDocument.setContentHash(task.contentHash());
        List<DocumentChunk> chunks = chunkService.chunkDocument(
                parsedDocument.getContent(),
                document.getStorageKey()
        );
        vectorIndexService.indexDocument(parsedDocument, document.getStorageKey(), chunks);
        persistenceService.markIndexingSuccess(task.documentId(), chunks);
        return "success";
    }

    private void handleFailure(
            DocumentIndexTask task,
            MapRecord<String, String, String> record,
            Exception exception
    ) {
        String reason = exception.getMessage() == null ? exception.toString() : exception.getMessage();
        logger.error(
                "Document indexing failed: {} attempt {} reason {}",
                task.documentId(),
                task.attempt(),
                reason,
                exception
        );
        if (!(exception instanceof NonRetryableDocumentIndexException) && task.attempt() < maxAttempts) {
            publisher.enqueue(task.documentId(), task.contentHash(), task.nextAttempt());
            persistenceService.markIndexingRetrying(task.documentId(), task.nextAttempt(), reason);
            metricsService.recordDocumentIndexOutcome("retrying", task.attempt());
            metricsService.recordStreamRetry("document-index", exception.getClass().getSimpleName());
        } else {
            publisher.enqueueDeadLetter(task.documentId(), task.contentHash(), task.attempt(), reason);
            persistenceService.markIndexingFailed(task.documentId(), reason);
            metricsService.recordDocumentIndexOutcome("failed", task.attempt());
        }
        acknowledge(record);
    }

    private boolean ensureConsumerGroup() {
        try {
            redisTemplate.opsForStream().createGroup(streamKey, ReadOffset.from("0"), group);
            return true;
        } catch (RedisSystemException e) {
            if (isBusyGroup(e)) {
                return true;
            }
            logger.warn("Could not prepare Redis Stream consumer group: {}", e.getMessage());
            return false;
        }
    }

    private boolean isBusyGroup(RedisSystemException exception) {
        for (Throwable current = exception; current != null; current = current.getCause()) {
            if (current.getMessage() != null && current.getMessage().contains("BUSYGROUP")) {
                return true;
            }
        }
        return false;
    }

    private void acknowledge(MapRecord<String, String, String> record) {
        redisTemplate.opsForStream().acknowledge(streamKey, group, record.getId());
    }
}
