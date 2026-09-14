package org.example.controller;

import org.example.entity.OpsDiagnosisRun;
import org.example.service.ChatService;
import org.example.service.auth.AiQuotaService;
import org.example.service.auth.AuthUserService;
import org.example.service.chat.ChatTaskRegistry;
import org.example.service.ops.DiagnosisRunService;
import org.example.service.persistence.ChatSessionService;
import org.example.service.persistence.DiagnosisPersistenceService;
import org.example.service.stream.DiagnosisRunStreamPublisher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ChatControllerTest {

    private static final UUID RUN_ID = UUID.randomUUID();

    private final ChatService chatService = mock(ChatService.class);
    private final ChatSessionService chatSessionService = mock(ChatSessionService.class);
    private final DiagnosisPersistenceService diagnosisPersistenceService =
            mock(DiagnosisPersistenceService.class);
    private final DiagnosisRunStreamPublisher diagnosisRunStreamPublisher =
            mock(DiagnosisRunStreamPublisher.class);
    private final AuthUserService authUserService = mock(AuthUserService.class);
    private final AiQuotaService aiQuotaService = mock(AiQuotaService.class);
    private final ChatTaskRegistry chatTaskRegistry = new ChatTaskRegistry();
    private final DiagnosisRunService diagnosisRunService =
            new DiagnosisRunService(diagnosisPersistenceService, diagnosisRunStreamPublisher, aiQuotaService);
    private ChatController controller;

    @BeforeEach
    void setUp() {
        controller = new ChatController();
        ReflectionTestUtils.setField(controller, "chatService", chatService);
        ReflectionTestUtils.setField(controller, "chatSessionService", chatSessionService);
        ReflectionTestUtils.setField(controller, "diagnosisPersistenceService", diagnosisPersistenceService);
        ReflectionTestUtils.setField(controller, "diagnosisRunService", diagnosisRunService);
        ReflectionTestUtils.setField(controller, "authUserService", authUserService);
        ReflectionTestUtils.setField(controller, "chatTaskRegistry", chatTaskRegistry);
        ReflectionTestUtils.setField(controller, "aiQuotaService", aiQuotaService);
    }

    @Test
    void queueDiagnosisRunPersistsQueuedRunAndEnqueuesStreamTask() {
        OpsDiagnosisRun run = run(DiagnosisPersistenceService.STATUS_QUEUED);
        when(diagnosisPersistenceService.queueRun("Automated AIOps alert diagnosis", null)).thenReturn(run);

        OpsDiagnosisRun result = controller.queueDiagnosisRun(null);

        assertThat(result.getId()).isEqualTo(RUN_ID);
        verify(diagnosisRunStreamPublisher).enqueue(RUN_ID);
    }

    @Test
    void queueDiagnosisRunMarksRunFailedWhenRedisIsUnavailable() {
        OpsDiagnosisRun run = run(DiagnosisPersistenceService.STATUS_QUEUED);
        when(diagnosisPersistenceService.queueRun("Automated AIOps alert diagnosis", null)).thenReturn(run);
        when(diagnosisRunStreamPublisher.enqueue(RUN_ID)).thenThrow(new RuntimeException("Redis unavailable"));

        assertThatThrownBy(() -> controller.queueDiagnosisRun(null))
                .isInstanceOf(RuntimeException.class)
                .hasMessage("Redis unavailable");

        verify(diagnosisPersistenceService).failRun(RUN_ID, "java.lang.RuntimeException: Redis unavailable");
    }

    @Test
    void activeChatTasksAreScopedToCurrentUser() {
        UUID userId = UUID.randomUUID();
        when(authUserService.currentUserId()).thenReturn(Optional.of(userId));
        ChatTaskRegistry.ChatTask task = chatTaskRegistry.start("session-a", userId, "question", false);
        task.append("partial");

        ResponseEntity<ChatController.ApiResponse<java.util.List<ChatTaskRegistry.ChatTaskView>>> response =
                controller.listActiveChatTasks();

        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getCode()).isEqualTo(200);
        assertThat(response.getBody().getData()).hasSize(1);
        assertThat(response.getBody().getData().get(0).sessionId()).isEqualTo("session-a");
        assertThat(response.getBody().getData().get(0).partialAnswer()).isEqualTo("partial");
    }

    @Test
    void deleteSessionIsRejectedWhileChatTaskIsRunning() {
        UUID userId = UUID.randomUUID();
        when(authUserService.currentUserId()).thenReturn(Optional.of(userId));
        chatTaskRegistry.start("session-a", userId, "question", false);

        ResponseEntity<ChatController.ApiResponse<String>> response = controller.deleteSession("session-a");

        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getCode()).isEqualTo(500);
        assertThat(response.getBody().getMessage()).isEqualTo("该对话正在生成回复，请完成后再删除");
    }

    private OpsDiagnosisRun run(String status) {
        OpsDiagnosisRun run = new OpsDiagnosisRun();
        run.setId(RUN_ID);
        run.setRequestText("Automated AIOps alert diagnosis");
        run.setStatus(status);
        return run;
    }
}
