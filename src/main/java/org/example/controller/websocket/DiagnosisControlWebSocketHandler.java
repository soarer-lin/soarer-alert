package org.example.controller.websocket;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.dto.ops.DiagnosisControlResult;
import org.example.dto.ops.DiagnosisRunSummary;
import org.example.service.ops.DiagnosisControlService;
import org.example.service.ops.DiagnosisRunNotFoundException;
import org.example.service.ops.DiagnosisQueryService;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.util.UUID;

@Component
public class DiagnosisControlWebSocketHandler extends TextWebSocketHandler {

    private final DiagnosisQueryService queryService;
    private final DiagnosisControlService controlService;
    private final ObjectMapper objectMapper;

    public DiagnosisControlWebSocketHandler(
            DiagnosisQueryService queryService,
            DiagnosisControlService controlService,
            ObjectMapper objectMapper
    ) {
        this.queryService = queryService;
        this.controlService = controlService;
        this.objectMapper = objectMapper;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        String traceId = normalizeTraceId(session.getHandshakeHeaders().getFirst("X-Trace-Id"));
        session.getAttributes().put("traceId", traceId);
        MDC.put("traceId", traceId);
        MDC.put("sessionId", session.getId());
        try {
            session.sendMessage(new TextMessage(objectMapper.writeValueAsString(new ControlAck("ready"))));
        } finally {
            MDC.clear();
        }
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        ControlCommand command;
        try {
            command = objectMapper.readValue(message.getPayload(), ControlCommand.class);
        } catch (JsonProcessingException e) {
            send(session, new ControlError("INVALID_MESSAGE", "控制消息格式错误"));
            return;
        }

        if (command.command() == null || command.command().isBlank()) {
            send(session, new ControlError("INVALID_COMMAND", "command 不能为空"));
            return;
        }
        if (command.runId() == null) {
            send(session, new ControlError("INVALID_RUN_ID", "runId 不能为空"));
            return;
        }

        String normalizedCommand = command.command().trim().toLowerCase();
        UUID runId;
        try {
            runId = UUID.fromString(command.runId());
        } catch (IllegalArgumentException e) {
            send(session, new ControlError("INVALID_RUN_ID", "runId 必须是 UUID"));
            return;
        }

        try {
            MDC.put("traceId", String.valueOf(session.getAttributes().getOrDefault("traceId", "")));
            MDC.put("sessionId", session.getId());
            MDC.put("diagnosisRunId", runId.toString());
            try {
                switch (normalizedCommand) {
                    case "status" -> {
                        DiagnosisRunSummary run = queryService.getRunSummary(runId);
                        send(session, new StatusResult(run.id(), run.status()));
                    }
                    case "cancel" -> send(session, controlService.cancel(runId));
                    case "pause", "resume" -> send(session, controlService.unsupportedControl(runId, normalizedCommand));
                    default -> send(session, new ControlError("UNKNOWN_COMMAND", "不支持的 command: " + normalizedCommand));
                }
            } finally {
                MDC.clear();
            }
        } catch (DiagnosisRunNotFoundException e) {
            send(session, new ControlError("NOT_FOUND", e.getMessage()));
        }
    }

    private void send(WebSocketSession session, Object payload) throws Exception {
        session.sendMessage(new TextMessage(objectMapper.writeValueAsString(payload)));
    }

    public record ControlCommand(String command, String runId) {
    }

    public record ControlAck(String type) {
    }

    public record ControlError(String error, String message) {
    }

    public record StatusResult(UUID runId, String status) {
    }

    private String normalizeTraceId(String value) {
        if (value == null || value.isBlank()) {
            return UUID.randomUUID().toString().replace("-", "");
        }
        String normalized = value.strip();
        return normalized.length() <= 64 ? normalized : normalized.substring(0, 64);
    }
}
