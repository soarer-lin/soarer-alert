package com.soarer.alert.service.stream;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.soarer.alert.entity.OpsAgentStep;
import com.soarer.alert.entity.OpsDiagnosisRun;
import com.soarer.alert.observability.DiagnosisAgentLifecycleHook;
import com.soarer.alert.service.AiOpsService;
import com.soarer.alert.service.ChatService;
import com.soarer.alert.service.observability.OpsMetricsService;
import com.soarer.alert.service.persistence.DiagnosisPersistenceService;
import org.slf4j.MDC;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.ai.tool.ToolCallback;
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
import java.util.Optional;
import java.util.UUID;

@Service
@ConditionalOnProperty(
        prefix = "app.diagnosis-stream",
        name = "enabled",
        havingValue = "true",
        matchIfMissing = true
)
public class DiagnosisRunStreamWorker {

    private static final Logger logger = LoggerFactory.getLogger(DiagnosisRunStreamWorker.class);

    private final StringRedisTemplate redisTemplate;
    private final DiagnosisRunStreamPublisher publisher;
    private final DiagnosisPersistenceService persistenceService;
    private final ChatService chatService;
    private final AiOpsService aiOpsService;
    private final OpsMetricsService metricsService;

    @Value("${app.diagnosis-stream.stream-key:soarer:diagnosis:stream}")
    private String streamKey;

    @Value("${app.diagnosis-stream.group:diagnosis-workers}")
    private String group;

    @Value("${app.diagnosis-stream.consumer-name:local-diagnosis-worker-1}")
    private String consumerName;

    @Value("${app.diagnosis-stream.max-attempts:3}")
    private int maxAttempts;

    @Value("${app.diagnosis-stream.batch-size:1}")
    private int batchSize;

    @Value("${app.diagnosis-stream.poll-timeout:PT1S}")
    private Duration pollTimeout;

    @Value("${app.diagnosis-stream.claim-timeout:PT2M}")
    private Duration claimTimeout;

    public DiagnosisRunStreamWorker(
            StringRedisTemplate redisTemplate,
            DiagnosisRunStreamPublisher publisher,
            DiagnosisPersistenceService persistenceService,
            ChatService chatService,
            AiOpsService aiOpsService,
            OpsMetricsService metricsService
    ) {
        this.redisTemplate = redisTemplate;
        this.publisher = publisher;
        this.persistenceService = persistenceService;
        this.chatService = chatService;
        this.aiOpsService = aiOpsService;
        this.metricsService = metricsService;
    }

    @Scheduled(fixedDelayString = "${app.diagnosis-stream.poll-interval-ms:1000}")
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

    @Scheduled(fixedDelayString = "${app.diagnosis-stream.recovery-interval-ms:30000}")
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
        DiagnosisRunTask task = null;
        try {
            task = DiagnosisRunTask.from(record.getValue());
            MDC.put("diagnosisRunId", task.diagnosisRunId().toString());
            MDC.put("attempt", Integer.toString(task.attempt()));
            String outcome = processTask(task);
            metricsService.recordDiagnosisOutcome(outcome, task.attempt());
            logger.info("Diagnosis run completed with outcome {}: {} attempt {}",
                    outcome, task.diagnosisRunId(), task.attempt());
            acknowledge(record);
        } catch (Exception e) {
            if (task == null) {
                logger.warn("Invalid diagnosis message {}: {}", record.getId(), e.getMessage());
                publisher.enqueueDeadLetter(null, 1, "Invalid message: " + e.getMessage());
                metricsService.recordDiagnosisOutcome("invalid", 1);
                acknowledge(record);
            } else {
                handleFailure(task, record, e);
            }
        } finally {
            MDC.remove("diagnosisRunId");
            MDC.remove("attempt");
            MDC.remove("agentStepId");
            DiagnosisAgentLifecycleHook.clearThreadState();
        }
    }

    String processTask(DiagnosisRunTask task) throws Exception {
        OpsDiagnosisRun run = persistenceService.getRun(task.diagnosisRunId())
                .orElseThrow(() -> new NonRetryableDiagnosisRunException("诊断任务不存在: " + task.diagnosisRunId()));
        if (DiagnosisPersistenceService.STATUS_SUCCESS.equals(run.getStatus())) {
            logger.info("Diagnosis run already completed, skipping task: {}", task.diagnosisRunId());
            return "skipped";
        }
        if (DiagnosisPersistenceService.STATUS_CANCELED.equals(run.getStatus())) {
            logger.info("Diagnosis run canceled, skipping task: {}", task.diagnosisRunId());
            return "canceled";
        }

        persistenceService.markRunRunning(task.diagnosisRunId());
        OpsAgentStep supervisorStep = persistenceService.startStep(
                task.diagnosisRunId(),
                "ai_ops_supervisor",
                "ORCHESTRATION",
                "Plan, execute, and replan automated alert diagnosis",
                run.getRequestText()
        );
        try {
            MDC.put("agentStepId", supervisorStep.getId().toString());
            OpenAiApi openAiApi = chatService.createChatApi();
            ChatModel chatModel = chatService.createChatModel(openAiApi, 0.3, 8000, 0.9);
            ToolCallback[] toolCallbacks = chatService.getToolCallbacks();

            long agentStarted = metricsService.startClock();
            Optional<OverAllState> stateOptional;
            try {
                stateOptional = aiOpsService.executeAiOpsAnalysis(chatModel, toolCallbacks);
                metricsService.recordAgentInvocation(agentStarted, "ai_ops_supervisor", "success");
            } catch (Exception e) {
                metricsService.recordAgentInvocation(agentStarted, "ai_ops_supervisor", "failed");
                throw e;
            }
            OverAllState state = stateOptional
                    .orElseThrow(() -> new DiagnosisRunExecutionException("No valid multi-agent result"));
            String report = aiOpsService.extractFinalReport(state)
                    .orElseThrow(() -> new DiagnosisRunExecutionException(
                            "Multi-agent flow completed without a final report"
                    ));
            if (isModelFailureText(report)) {
                String failure = report.lines().findFirst().orElse("AI model call failed");
                throw new NonRetryableDiagnosisRunException("AI model call failed: " + failure);
            }

            if (isCanceled(task.diagnosisRunId())) {
                failStepQuietly(supervisorStep, new IllegalStateException("诊断任务已被取消"));
                return "canceled";
            }

            persistenceService.completeStep(supervisorStep.getId(), report);
            persistenceService.completeRun(task.diagnosisRunId(), report);
            return "success";
        } catch (Exception e) {
            failStepQuietly(supervisorStep, e);
            throw e;
        } finally {
            MDC.remove("agentStepId");
        }
    }

    private void handleFailure(
            DiagnosisRunTask task,
            MapRecord<String, String, String> record,
            Exception exception
    ) {
        String reason = exception.getMessage() == null ? exception.toString() : exception.getMessage();
        logger.error(
                "Diagnosis run failed: {} attempt {} reason {}",
                task.diagnosisRunId(),
                task.attempt(),
                reason,
                exception
        );
        int nextAttempt = task.attempt() + 1;
        if (!(exception instanceof NonRetryableDiagnosisRunException) && task.attempt() < maxAttempts) {
            publisher.enqueue(task.diagnosisRunId(), nextAttempt);
            persistenceService.markRunRetrying(task.diagnosisRunId(), nextAttempt, reason);
            metricsService.recordDiagnosisOutcome("retrying", task.attempt());
            metricsService.recordStreamRetry("diagnosis", exception.getClass().getSimpleName());
        } else {
            publisher.enqueueDeadLetter(task.diagnosisRunId(), task.attempt(), reason);
            failRunQuietly(task.diagnosisRunId(), reason);
            metricsService.recordDiagnosisOutcome("failed", task.attempt());
        }
        acknowledge(record);
    }

    private void failRunQuietly(UUID runId, String reason) {
        try {
            persistenceService.failRun(runId, reason);
        } catch (Exception persistenceException) {
            logger.error("Failed to persist diagnosis run failure", persistenceException);
        }
    }

    private static boolean isModelFailureText(String report) {
        return report != null && report.trim().startsWith("Exception:");
    }

    private boolean isCanceled(UUID runId) {
        return persistenceService.getRun(runId)
                .map(run -> DiagnosisPersistenceService.STATUS_CANCELED.equals(run.getStatus()))
                .orElse(false);
    }

    private void failStepQuietly(OpsAgentStep step, Throwable failure) {
        if (step.getId() == null) {
            return;
        }
        try {
            persistenceService.failStep(step.getId(), failure.toString());
        } catch (Exception persistenceException) {
            logger.error("Failed to persist diagnosis step failure", persistenceException);
        }
    }

    private boolean ensureConsumerGroup() {
        try {
            redisTemplate.opsForStream().createGroup(streamKey, ReadOffset.from("0"), group);
            return true;
        } catch (RedisSystemException e) {
            if (isBusyGroup(e)) {
                return true;
            }
            logger.warn("Could not prepare diagnosis Redis Stream consumer group: {}", e.getMessage());
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

    private static class NonRetryableDiagnosisRunException extends RuntimeException {
        private NonRetryableDiagnosisRunException(String message) {
            super(message);
        }
    }

    private static class DiagnosisRunExecutionException extends RuntimeException {
        private DiagnosisRunExecutionException(String message) {
            super(message);
        }
    }
}
