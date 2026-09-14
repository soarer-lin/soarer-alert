package org.example.controller.websocket;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.dto.ops.DiagnosisRunSummary;
import org.example.service.ops.DiagnosisControlService;
import org.example.service.ops.DiagnosisRunNotFoundException;
import org.example.service.ops.DiagnosisQueryService;
import org.example.service.persistence.DiagnosisPersistenceService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpHeaders;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.util.concurrent.ConcurrentHashMap;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DiagnosisControlWebSocketHandlerTest {

    private static final UUID RUN_ID = UUID.randomUUID();

    private final DiagnosisQueryService queryService = mock(DiagnosisQueryService.class);
    private final DiagnosisControlService controlService = mock(DiagnosisControlService.class);
    private final WebSocketSession session = mock(WebSocketSession.class);
    private final ObjectMapper objectMapper = new ObjectMapper();
    private DiagnosisControlWebSocketHandler handler;

    @BeforeEach
    void setUp() {
        handler = new DiagnosisControlWebSocketHandler(queryService, controlService, objectMapper);
        when(session.getHandshakeHeaders()).thenReturn(new HttpHeaders());
        when(session.getId()).thenReturn("test-session");
        when(session.getAttributes()).thenReturn(new ConcurrentHashMap<>());
    }

    @Test
    void afterConnectionEstablishedSendsReadyAck() throws Exception {
        handler.afterConnectionEstablished(session);

        TextMessage message = capturedMessage();
        JsonNode payload = objectMapper.readTree(message.getPayload());
        assertThat(payload.get("type").asText()).isEqualTo("ready");
    }

    @Test
    void statusCommandReturnsRunStatus() throws Exception {
        when(queryService.getRunSummary(RUN_ID)).thenReturn(runSummary());

        handler.handleTextMessage(session, new TextMessage(command("status")));

        JsonNode payload = objectMapper.readTree(capturedMessage().getPayload());
        assertThat(payload.get("runId").asText()).isEqualTo(RUN_ID.toString());
        assertThat(payload.get("status").asText()).isEqualTo("RUNNING");
    }

    @Test
    void missingRunReturnsErrorWithoutClosingSession() throws Exception {
        when(queryService.getRunSummary(RUN_ID)).thenThrow(new DiagnosisRunNotFoundException(RUN_ID));

        handler.handleTextMessage(session, new TextMessage(command("status")));

        JsonNode payload = objectMapper.readTree(capturedMessage().getPayload());
        assertThat(payload.get("error").asText()).isEqualTo("NOT_FOUND");
        assertThat(payload.get("message").asText()).isEqualTo("诊断任务不存在: " + RUN_ID);
        verify(session, org.mockito.Mockito.never()).close();
    }

    private TextMessage capturedMessage() throws Exception {
        ArgumentCaptor<TextMessage> captor = ArgumentCaptor.forClass(TextMessage.class);
        verify(session).sendMessage(captor.capture());
        return captor.getValue();
    }

    private String command(String command) {
        return "{\"command\":\"" + command + "\",\"runId\":\"" + RUN_ID + "\"}";
    }

    private DiagnosisRunSummary runSummary() {
        return new DiagnosisRunSummary(
                RUN_ID,
                "diagnose CPU alert",
                DiagnosisPersistenceService.STATUS_RUNNING,
                null,
                null,
                null,
                null
        );
    }
}
