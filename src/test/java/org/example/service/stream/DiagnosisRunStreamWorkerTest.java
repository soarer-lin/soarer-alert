package org.example.service.stream;

import com.alibaba.cloud.ai.graph.OverAllState;
import org.example.entity.OpsAgentStep;
import org.example.entity.OpsDiagnosisRun;
import org.example.service.AiOpsService;
import org.example.service.ChatService;
import org.example.service.observability.OpsMetricsService;
import org.example.service.persistence.DiagnosisPersistenceService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.ai.tool.ToolCallback;
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
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DiagnosisRunStreamWorkerTest {

    private static final String STREAM_KEY = "soarer:diagnosis:stream";
    private static final String GROUP = "diagnosis-workers";
    private static final UUID RUN_ID = UUID.randomUUID();

    private final StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
    private final DiagnosisRunStreamPublisher publisher = mock(DiagnosisRunStreamPublisher.class);
    private final DiagnosisPersistenceService persistenceService = mock(DiagnosisPersistenceService.class);
    private final ChatService chatService = mock(ChatService.class);
    private final AiOpsService aiOpsService = mock(AiOpsService.class);
    private final OpsMetricsService metricsService =
            new OpsMetricsService(new SimpleMeterRegistry());
    private final OpenAiApi openAiApi = mock(OpenAiApi.class);
    private final ChatModel chatModel = mock(ChatModel.class);
    private final OverAllState overAllState = mock(OverAllState.class);
    @SuppressWarnings("unchecked")
    private final StreamOperations<String, String, String> streamOperations =
            mock(StreamOperations.class);
    private DiagnosisRunStreamWorker worker;

    @BeforeEach
    void setUp() {
        worker = new DiagnosisRunStreamWorker(
                redisTemplate,
                publisher,
                persistenceService,
                chatService,
                aiOpsService,
                metricsService
        );
        ReflectionTestUtils.setField(worker, "streamKey", STREAM_KEY);
        ReflectionTestUtils.setField(worker, "group", GROUP);
        ReflectionTestUtils.setField(worker, "consumerName", "test-worker");
        ReflectionTestUtils.setField(worker, "maxAttempts", 3);
        ReflectionTestUtils.setField(worker, "batchSize", 1);
        ReflectionTestUtils.setField(worker, "pollTimeout", Duration.ofSeconds(1));
        ReflectionTestUtils.setField(worker, "claimTimeout", Duration.ofMinutes(2));
        doReturn(streamOperations).when(redisTemplate).opsForStream();
        when(chatService.createChatApi()).thenReturn(openAiApi);
        when(chatService.createChatModel(openAiApi, 0.3, 8000, 0.9)).thenReturn(chatModel);
        when(chatService.getToolCallbacks()).thenReturn(new ToolCallback[0]);
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
    void processesSuccessfulDiagnosisAndAcknowledgesRecord() throws Exception {
        OpsDiagnosisRun run = run(DiagnosisPersistenceService.STATUS_QUEUED);
        OpsAgentStep step = step();
        MapRecord<String, String, String> record = taskRecord(1);

        when(persistenceService.getRun(RUN_ID)).thenReturn(Optional.of(run));
        when(persistenceService.startStep(
                eq(RUN_ID),
                eq("ai_ops_supervisor"),
                eq("ORCHESTRATION"),
                anyString(),
                anyString()
        )).thenReturn(step);
        when(aiOpsService.executeAiOpsAnalysis(chatModel, new ToolCallback[0]))
                .thenReturn(Optional.of(overAllState));
        when(aiOpsService.extractFinalReport(overAllState)).thenReturn(Optional.of("final report"));

        worker.processRecord(record);

        verify(persistenceService).markRunRunning(RUN_ID);
        verify(persistenceService).completeStep(step.getId(), "final report");
        verify(persistenceService).completeRun(RUN_ID, "final report");
        verify(streamOperations).acknowledge(STREAM_KEY, GROUP, record.getId());
        verify(publisher, never()).enqueue(any(UUID.class), any(Integer.class));
        verify(publisher, never()).enqueueDeadLetter(any(UUID.class), any(Integer.class), anyString());
    }

    @Test
    void retriesFailedDiagnosisWithNextAttempt() throws Exception {
        OpsDiagnosisRun run = run(DiagnosisPersistenceService.STATUS_QUEUED);
        OpsAgentStep step = step();
        MapRecord<String, String, String> record = taskRecord(1);

        when(persistenceService.getRun(RUN_ID)).thenReturn(Optional.of(run));
        when(persistenceService.startStep(
                eq(RUN_ID),
                eq("ai_ops_supervisor"),
                eq("ORCHESTRATION"),
                anyString(),
                anyString()
        )).thenReturn(step);
        when(aiOpsService.executeAiOpsAnalysis(chatModel, new ToolCallback[0]))
                .thenThrow(new RuntimeException("model failed"));

        worker.processRecord(record);

        verify(persistenceService).failStep(step.getId(), "java.lang.RuntimeException: model failed");
        verify(publisher).enqueue(RUN_ID, 2);
        verify(persistenceService).markRunRetrying(RUN_ID, 2, "model failed");
        verify(streamOperations).acknowledge(STREAM_KEY, GROUP, record.getId());
        verify(publisher, never()).enqueueDeadLetter(any(UUID.class), any(Integer.class), anyString());
    }

    @Test
    void failsWithoutRetryWhenFinalReportIsModelException() throws Exception {
        OpsDiagnosisRun run = run(DiagnosisPersistenceService.STATUS_QUEUED);
        OpsAgentStep step = step();
        MapRecord<String, String, String> record = taskRecord(1);

        when(persistenceService.getRun(RUN_ID)).thenReturn(Optional.of(run));
        when(persistenceService.startStep(
                eq(RUN_ID),
                eq("ai_ops_supervisor"),
                eq("ORCHESTRATION"),
                anyString(),
                anyString()
        )).thenReturn(step);
        when(aiOpsService.executeAiOpsAnalysis(chatModel, new ToolCallback[0]))
                .thenReturn(Optional.of(overAllState));
        when(aiOpsService.extractFinalReport(overAllState))
                .thenReturn(Optional.of("Exception: 403 - model quota exhausted"));

        worker.processRecord(record);

        verify(persistenceService).failStep(
                step.getId(),
                "org.example.service.stream.DiagnosisRunStreamWorker$NonRetryableDiagnosisRunException: AI model call failed: Exception: 403 - model quota exhausted"
        );
        verify(publisher).enqueueDeadLetter(
                eq(RUN_ID),
                eq(1),
                eq("AI model call failed: Exception: 403 - model quota exhausted")
        );
        verify(persistenceService).failRun(
                RUN_ID,
                "AI model call failed: Exception: 403 - model quota exhausted"
        );
        verify(persistenceService, never()).completeRun(any(UUID.class), anyString());
        verify(streamOperations).acknowledge(STREAM_KEY, GROUP, record.getId());
    }

    @Test
    void sendsDiagnosisToDeadLetterAfterMaxAttempts() throws Exception {
        OpsDiagnosisRun run = run(DiagnosisPersistenceService.STATUS_RETRYING);
        OpsAgentStep step = step();
        MapRecord<String, String, String> record = taskRecord(3);

        when(persistenceService.getRun(RUN_ID)).thenReturn(Optional.of(run));
        when(persistenceService.startStep(
                eq(RUN_ID),
                eq("ai_ops_supervisor"),
                eq("ORCHESTRATION"),
                anyString(),
                anyString()
        )).thenReturn(step);
        when(aiOpsService.executeAiOpsAnalysis(chatModel, new ToolCallback[0]))
                .thenThrow(new RuntimeException("model failed"));

        worker.processRecord(record);

        verify(persistenceService).failStep(step.getId(), "java.lang.RuntimeException: model failed");
        verify(publisher).enqueueDeadLetter(RUN_ID, 3, "model failed");
        verify(persistenceService).failRun(RUN_ID, "model failed");
        verify(streamOperations).acknowledge(STREAM_KEY, GROUP, record.getId());
        verify(publisher, never()).enqueue(any(UUID.class), any(Integer.class));
    }

    @Test
    void skipsAlreadySuccessfulDiagnosisWithoutInvokingAgents() throws Exception {
        OpsDiagnosisRun run = run(DiagnosisPersistenceService.STATUS_SUCCESS);
        MapRecord<String, String, String> record = taskRecord(1);

        when(persistenceService.getRun(RUN_ID)).thenReturn(Optional.of(run));

        worker.processRecord(record);

        verify(persistenceService, never()).markRunRunning(RUN_ID);
        verify(persistenceService, never()).startStep(any(UUID.class), anyString(), anyString(), anyString(), anyString());
        verify(chatService, never()).createChatApi();
        verify(aiOpsService, never()).executeAiOpsAnalysis(any(), any());
        verify(streamOperations).acknowledge(STREAM_KEY, GROUP, record.getId());
    }

    @Test
    void skipsCanceledDiagnosisBeforeStartingWork() throws Exception {
        OpsDiagnosisRun run = run(DiagnosisPersistenceService.STATUS_CANCELED);
        MapRecord<String, String, String> record = taskRecord(1);

        when(persistenceService.getRun(RUN_ID)).thenReturn(Optional.of(run));

        worker.processRecord(record);

        verify(persistenceService, never()).markRunRunning(RUN_ID);
        verify(persistenceService, never()).startStep(any(UUID.class), anyString(), anyString(), anyString(), anyString());
        verify(chatService, never()).createChatApi();
        verify(aiOpsService, never()).executeAiOpsAnalysis(any(), any());
        verify(streamOperations).acknowledge(STREAM_KEY, GROUP, record.getId());
    }

    @Test
    void doesNotOverwriteCanceledRunWithSuccessfulResult() throws Exception {
        OpsDiagnosisRun queuedRun = run(DiagnosisPersistenceService.STATUS_QUEUED);
        OpsDiagnosisRun canceledRun = run(DiagnosisPersistenceService.STATUS_CANCELED);
        OpsAgentStep step = step();
        MapRecord<String, String, String> record = taskRecord(1);

        when(persistenceService.getRun(RUN_ID)).thenReturn(Optional.of(queuedRun), Optional.of(canceledRun));
        when(persistenceService.startStep(
                eq(RUN_ID),
                eq("ai_ops_supervisor"),
                eq("ORCHESTRATION"),
                anyString(),
                anyString()
        )).thenReturn(step);
        when(aiOpsService.executeAiOpsAnalysis(chatModel, new ToolCallback[0]))
                .thenReturn(Optional.of(overAllState));
        when(aiOpsService.extractFinalReport(overAllState)).thenReturn(Optional.of("final report"));

        worker.processRecord(record);

        verify(persistenceService).failStep(
                step.getId(),
                "java.lang.IllegalStateException: 诊断任务已被取消"
        );
        verify(persistenceService, never()).completeStep(any(UUID.class), anyString());
        verify(persistenceService, never()).completeRun(any(UUID.class), anyString());
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
                eq(1),
                argThat(reason -> reason != null && reason.startsWith("Invalid message:"))
        );
        verify(persistenceService, never()).getRun(any(UUID.class));
        verify(streamOperations).acknowledge(STREAM_KEY, GROUP, record.getId());
    }

    @Test
    void acknowledgesMissingRunMessageAfterWritingDeadLetter() {
        MapRecord<String, String, String> record = taskRecord(1);
        when(persistenceService.getRun(RUN_ID)).thenReturn(Optional.empty());
        doThrow(new IllegalArgumentException("诊断任务不存在: " + RUN_ID))
                .when(persistenceService).failRun(RUN_ID, "诊断任务不存在: " + RUN_ID);

        worker.processRecord(record);

        verify(publisher).enqueueDeadLetter(RUN_ID, 1, "诊断任务不存在: " + RUN_ID);
        verify(streamOperations).acknowledge(STREAM_KEY, GROUP, record.getId());
    }

    private MapRecord<String, String, String> taskRecord(int attempt) {
        Map<String, String> fields = new HashMap<>();
        fields.put("diagnosisRunId", RUN_ID.toString());
        fields.put("attempt", Integer.toString(attempt));
        return MapRecord.<String, String, String>create(STREAM_KEY, fields)
                .withId(RecordId.of("1-1"));
    }

    private OpsDiagnosisRun run(String status) {
        OpsDiagnosisRun run = new OpsDiagnosisRun();
        run.setId(RUN_ID);
        run.setRequestText("Automated AIOps alert diagnosis");
        run.setStatus(status);
        return run;
    }

    private OpsAgentStep step() {
        OpsAgentStep step = new OpsAgentStep();
        step.setId(UUID.randomUUID());
        step.setDiagnosisRunId(RUN_ID);
        step.setStepIndex(1);
        step.setAgentName("ai_ops_supervisor");
        step.setStepType("ORCHESTRATION");
        step.setStatus(DiagnosisPersistenceService.STATUS_RUNNING);
        return step;
    }
}
