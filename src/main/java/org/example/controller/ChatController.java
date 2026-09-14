package org.example.controller;

import com.alibaba.cloud.ai.graph.NodeOutput;
import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.alibaba.cloud.ai.graph.streaming.OutputType;
import com.alibaba.cloud.ai.graph.streaming.StreamingOutput;
import lombok.Getter;
import lombok.Setter;
import org.example.entity.OpsDiagnosisReport;
import org.example.entity.OpsAgentStep;
import org.example.entity.OpsDiagnosisRun;
import org.example.service.ChatService;
import org.example.service.auth.AiQuotaExhaustedException;
import org.example.service.auth.AiQuotaService;
import org.example.service.auth.AuthUserService;
import org.example.service.chat.ChatTaskRegistry;
import org.example.service.persistence.DiagnosisPersistenceService;
import org.example.service.persistence.ChatSessionService;
import org.example.service.ops.DiagnosisRunService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.publisher.Flux;

import java.io.IOException;
import java.time.ZoneId;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 统一 API 控制器
 * 适配前端接口需求
 */
@RestController
@RequestMapping("/api")
public class ChatController {

    private static final Logger logger = LoggerFactory.getLogger(ChatController.class);

    @Autowired
    private ChatService chatService;

    @Autowired
    private ChatSessionService chatSessionService;

    @Autowired
    private DiagnosisRunService diagnosisRunService;

    @Autowired
    private DiagnosisPersistenceService diagnosisPersistenceService;

    @Autowired
    private AuthUserService authUserService;

    @Autowired
    private ChatTaskRegistry chatTaskRegistry;

    @Autowired
    private AiQuotaService aiQuotaService;

    private final ExecutorService executor = Executors.newCachedThreadPool();

    /**
     * 普通对话接口（支持工具调用）
     * 与 /chat_react 逻辑一致，但直接返回完整结果而非流式输出
     */
    @PostMapping("/chat")
    public ResponseEntity<ApiResponse<ChatResponse>> chat(@RequestBody ChatRequest request) {
        UUID currentUserId = null;
        boolean regenerate = false;
        Integer assistantSequence = null;
        try {
            currentUserId = authUserService.currentUserId().orElse(null);
            logger.info("收到对话请求 - SessionId: {}, Question: {}", request.getId(), request.getQuestion());

            // 参数校验
            if (request.getQuestion() == null || request.getQuestion().trim().isEmpty()) {
                logger.warn("问题内容为空");
                return ResponseEntity.ok(ApiResponse.success(ChatResponse.error("问题内容不能为空")));
            }

            regenerate = request.isRegenerate();
            assistantSequence = request.getAssistantSequence();
            if (regenerate && (assistantSequence == null || assistantSequence < 1)) {
                return ResponseEntity.ok(ApiResponse.success(ChatResponse.error("重新生成参数无效")));
            }
            if (!chatService.isDashScopeApiKeyConfigured()) {
                return ResponseEntity.ok(ApiResponse.success(ChatResponse.error(
                        "DashScope API Key 未配置，请设置环境变量 DASHSCOPE_API_KEY 后重启应用"
                )));
            }

            // 重新生成时只使用目标问题之前的上下文，避免把旧答案再次带入模型。
            List<Map<String, String>> history = regenerate
                    ? chatSessionService.getRecentHistoryBefore(
                    request.getId(), currentUserId, assistantSequence - 1)
                    : chatSessionService.getRecentHistory(request.getId(), currentUserId);
            logger.info("会话历史消息对数: {}", history.size() / 2);

            aiQuotaService.consume(currentUserId);

            // 创建 DashScope API 和 ChatModel
            OpenAiApi openAiApi = chatService.createChatApi();
            ChatModel chatModel = chatService.createStandardChatModel(openAiApi);

            // 记录可用工具
            chatService.logAvailableTools();

            logger.info("开始 ReactAgent 对话（支持自动工具调用）");
            
            // 构建系统提示词（包含历史消息）
            String systemPrompt = chatService.buildSystemPrompt(history);
            
            // 创建 ReactAgent
            ReactAgent agent = chatService.createReactAgent(chatModel, systemPrompt);
            
            // 执行对话
            String fullAnswer = chatService.executeChat(agent, request.getQuestion());
            
            if (regenerate) {
                chatSessionService.replaceAssistantAnswer(
                        request.getId(), currentUserId, assistantSequence, fullAnswer);
            } else {
                chatSessionService.addExchange(request.getId(), currentUserId, request.getQuestion(), fullAnswer);
            }
            logger.info("已更新数据库会话历史 - SessionId: {}", request.getId());
            
            return ResponseEntity.ok(ApiResponse.success(ChatResponse.success(fullAnswer)));

        } catch (AiQuotaExhaustedException e) {
            persistQuotaExhaustedChat(
                    request.getId(),
                    currentUserId,
                    request.getQuestion(),
                    regenerate,
                    assistantSequence,
                    e.getMessage()
            );
            return ResponseEntity.ok(ApiResponse.success(
                    ChatResponse.error(e.getMessage(), AiQuotaExhaustedException.ERROR_CODE)
            ));
        } catch (Exception e) {
            logger.error("对话失败", e);
            return ResponseEntity.ok(ApiResponse.success(ChatResponse.error(e.getMessage())));
        }
    }

    /**
     * 清空会话历史
     */
    @PostMapping("/chat/clear")
    public ResponseEntity<ApiResponse<String>> clearChatHistory(@RequestBody ClearRequest request) {
        try {
            UUID currentUserId = authUserService.currentUserId().orElse(null);
            logger.info("收到清空会话历史请求 - SessionId: {}", request.getId());

            if (request.getId() == null || request.getId().isEmpty()) {
                return ResponseEntity.ok(ApiResponse.error("会话ID不能为空"));
            }

            boolean cleared = chatSessionService.clearHistory(request.getId(), currentUserId);
            if (cleared) {
                return ResponseEntity.ok(ApiResponse.success("会话历史已清空"));
            } else {
                return ResponseEntity.ok(ApiResponse.error("会话不存在"));
            }

        } catch (Exception e) {
            logger.error("清空会话历史失败", e);
            return ResponseEntity.ok(ApiResponse.error(e.getMessage()));
        }
    }

    /**
     * ReactAgent 对话接口（SSE 流式模式，支持多轮对话，支持自动工具调用，例如获取当前时间，查询日志，告警等）
     * 支持 session 管理，保留对话历史
     */
    @PostMapping(value = "/chat_stream", produces = "text/event-stream;charset=UTF-8")
    public SseEmitter chatStream(@RequestBody ChatRequest request) {
        SseEmitter emitter = new SseEmitter(300000L); // 5分钟超时
        UUID currentUserId = authUserService.currentUserId().orElse(null);
        final boolean regenerate = request.isRegenerate();
        final Integer assistantSequence = request.getAssistantSequence();
        AtomicBoolean emitterActive = new AtomicBoolean(true);
        emitter.onCompletion(() -> emitterActive.set(false));
        emitter.onTimeout(() -> emitterActive.set(false));
        emitter.onError(error -> emitterActive.set(false));

        // 参数校验
        if (request.getQuestion() == null || request.getQuestion().trim().isEmpty()) {
            logger.warn("问题内容为空");
            try {
                emitter.send(SseEmitter.event().name("message").data(SseMessage.error("问题内容不能为空"), MediaType.APPLICATION_JSON));
                emitter.complete();
            } catch (IOException e) {
                emitter.completeWithError(e);
            }
            return emitter;
        }
        if (regenerate && (assistantSequence == null || assistantSequence < 1)) {
            try {
                emitter.send(SseEmitter.event().name("message")
                        .data(SseMessage.error("重新生成参数无效"), MediaType.APPLICATION_JSON));
                emitter.complete();
            } catch (IOException e) {
                emitter.completeWithError(e);
            }
            return emitter;
        }
        if (!chatService.isDashScopeApiKeyConfigured()) {
            sendChatSse(
                    emitter,
                    SseMessage.error("DashScope API Key 未配置，请设置环境变量 DASHSCOPE_API_KEY 后重启应用"),
                    emitterActive
            );
            completeChatEmitter(emitter);
            return emitter;
        }

        final ChatTaskRegistry.ChatTask task;
        try {
            task = chatTaskRegistry.start(request.getId(), currentUserId, request.getQuestion(), regenerate);
        } catch (IllegalStateException exception) {
            sendChatSse(emitter, SseMessage.error(exception.getMessage()), emitterActive);
            completeChatEmitter(emitter);
            return emitter;
        }

        try {
            aiQuotaService.consume(currentUserId);
        } catch (AiQuotaExhaustedException e) {
            chatTaskRegistry.fail(task);
            persistQuotaExhaustedChat(
                    request.getId(),
                    currentUserId,
                    request.getQuestion(),
                    regenerate,
                    assistantSequence,
                    e.getMessage()
            );
            sendChatSse(
                    emitter,
                    SseMessage.error(e.getMessage(), AiQuotaExhaustedException.ERROR_CODE),
                    emitterActive
            );
            completeChatEmitter(emitter);
            return emitter;
        }

        executor.execute(() -> {
            AtomicBoolean userMessageSaved = new AtomicBoolean(false);
            try {
                logger.info("收到 ReactAgent 对话请求 - SessionId: {}, Question: {}", request.getId(), request.getQuestion());

                // 重新生成时只使用目标问题之前的上下文，避免把旧答案再次带入模型。
                List<Map<String, String>> history = regenerate
                        ? chatSessionService.getRecentHistoryBefore(
                        request.getId(), currentUserId, assistantSequence - 1)
                        : chatSessionService.getRecentHistory(request.getId(), currentUserId);
                logger.info("ReactAgent 会话历史消息对数: {}", history.size() / 2);

                // 创建 DashScope API 和 ChatModel
                OpenAiApi openAiApi = chatService.createChatApi();
                ChatModel chatModel = chatService.createStandardChatModel(openAiApi);

                // 记录可用工具
                chatService.logAvailableTools();

                logger.info("开始 ReactAgent 流式对话（支持自动工具调用）");
                
                // 构建系统提示词（包含历史消息）
                String systemPrompt = chatService.buildSystemPrompt(history);

                // 先保存用户问题，浏览器切换页面后历史中仍能看到本轮提问。
                if (!regenerate) {
                    chatSessionService.addUserMessage(
                            request.getId(), currentUserId, request.getQuestion());
                    userMessageSaved.set(true);
                }
                
                // 创建 ReactAgent
                ReactAgent agent = chatService.createReactAgent(chatModel, systemPrompt);
                
                // 用于累积完整答案
                StringBuilder fullAnswerBuilder = new StringBuilder();
                
                // 使用 agent.stream() 进行流式对话
                Flux<NodeOutput> stream = agent.stream(request.getQuestion());
                
                stream.subscribe(
                    output -> {
                        try {
                            // 检查是否为 StreamingOutput 类型
                            if (output instanceof StreamingOutput streamingOutput) {
                                OutputType type = streamingOutput.getOutputType();
                                
                                // 处理模型推理的流式输出
                                if (type == OutputType.AGENT_MODEL_STREAMING) {
                                    // 流式增量内容，逐步显示
                                    String chunk = streamingOutput.message().getText();
                                    if (chunk != null && !chunk.isEmpty()) {
                                        fullAnswerBuilder.append(chunk);
                                        task.append(chunk);
                                        
                                        // 实时发送到前端
                                        sendChatSse(
                                                emitter,
                                                SseMessage.content(chunk),
                                                emitterActive
                                        );
                                        
                                        logger.info("发送流式内容: {}", chunk);
                                    }
                                } else if (type == OutputType.AGENT_MODEL_FINISHED) {
                                    // 模型推理完成
                                    logger.info("模型输出完成");
                                } else if (type == OutputType.AGENT_TOOL_FINISHED) {
                                    // 工具调用完成
                                    logger.info("工具调用完成: {}", output.node());
                                } else if (type == OutputType.AGENT_HOOK_FINISHED) {
                                    // Hook 执行完成
                                    logger.debug("Hook 执行完成: {}", output.node());
                                }
                            }
                        } catch (Exception e) {
                            logger.error("处理流式消息失败", e);
                        }
                    },
                    error -> {
                        // 错误处理
                        logger.error("ReactAgent 流式对话失败", error);
                        if (userMessageSaved.get()) {
                            try {
                                chatSessionService.addAssistantAnswer(
                                        request.getId(),
                                        currentUserId,
                                        "抱歉，生成回复失败：" + errorMessage(error)
                                );
                            } catch (Exception persistenceError) {
                                logger.error("保存失败回复失败", persistenceError);
                            }
                        }
                        chatTaskRegistry.fail(task);
                        sendChatSse(emitter, SseMessage.error(errorMessage(error)), emitterActive);
                        completeChatEmitter(emitter);
                    },
                    () -> {
                        // 完成处理
                        try {
                            String fullAnswer = fullAnswerBuilder.toString();
                            logger.info("ReactAgent 流式对话完成 - SessionId: {}, 答案长度: {}", 
                                request.getId(), fullAnswer.length());
                            
                            if (regenerate) {
                                chatSessionService.replaceAssistantAnswer(
                                        request.getId(), currentUserId, assistantSequence, fullAnswer);
                            } else {
                                chatSessionService.addAssistantAnswer(
                                        request.getId(), currentUserId, fullAnswer);
                            }
                            logger.info("已更新数据库会话历史 - SessionId: {}", request.getId());
                            chatTaskRegistry.complete(task);
                            
                            // 发送完成标记
                            sendChatSse(emitter, SseMessage.done(), emitterActive);
                            completeChatEmitter(emitter);
                        } catch (Exception e) {
                            logger.error("保存流式对话结果失败", e);
                            chatTaskRegistry.fail(task);
                            sendChatSse(emitter, SseMessage.error("保存回复失败：" + errorMessage(e)), emitterActive);
                            completeChatEmitter(emitter);
                        }
                    }
                );

            } catch (Exception e) {
                logger.error("ReactAgent 对话初始化失败", e);
                if (userMessageSaved.get()) {
                    try {
                        chatSessionService.addAssistantAnswer(
                                request.getId(),
                                currentUserId,
                                "抱歉，生成回复失败：" + errorMessage(e)
                        );
                    } catch (Exception persistenceError) {
                        logger.error("保存失败回复失败", persistenceError);
                    }
                }
                chatTaskRegistry.fail(task);
                sendChatSse(emitter, SseMessage.error(errorMessage(e)), emitterActive);
                completeChatEmitter(emitter);
            }
        });

        return emitter;
    }

    private void sendChatSse(SseEmitter emitter, SseMessage message, AtomicBoolean emitterActive) {
        if (!emitterActive.get()) {
            return;
        }
        try {
            emitter.send(SseEmitter.event()
                    .name("message")
                    .data(message, MediaType.APPLICATION_JSON));
        } catch (Exception e) {
            emitterActive.set(false);
            logger.debug("聊天 SSE 客户端已断开，后台生成继续执行", e);
        }
    }

    private void completeChatEmitter(SseEmitter emitter) {
        try {
            emitter.complete();
        } catch (Exception ignored) {
            // The client may already be disconnected; generation continues server-side.
        }
    }

    private void persistQuotaExhaustedChat(
            String sessionId,
            UUID userId,
            String question,
            boolean regenerate,
            Integer assistantSequence,
            String answer
    ) {
        try {
            if (regenerate) {
                chatSessionService.replaceAssistantAnswer(sessionId, userId, assistantSequence, answer);
            } else {
                chatSessionService.addExchange(sessionId, userId, question, answer);
            }
        } catch (Exception e) {
            logger.error("保存额度耗尽提示失败", e);
        }
    }

    private String errorMessage(Throwable error) {
        String message = error.getMessage();
        return message == null || message.isBlank() ? "未知错误" : message;
    }

    /**
     * AI 智能运维接口（SSE 流式模式）- 自动分析告警并生成运维报告
     * 无需用户输入，自动执行告警分析流程
     */
    @PostMapping(value = "/ai_ops", produces = "text/event-stream;charset=UTF-8")
    public SseEmitter aiOps() {
        SseEmitter emitter = new SseEmitter(600000L); // 10分钟超时（告警分析可能较慢）
        UUID currentUserId = authUserService.currentUserId().orElse(null);

        executor.execute(() -> {
            streamDiagnosisResult(emitter, currentUserId);
        });

        return emitter;
    }

    OpsDiagnosisRun queueDiagnosisRun(UUID createdBy) {
        return diagnosisRunService.queueRun(DiagnosisRunService.DEFAULT_REQUEST_TEXT, createdBy);
    }

    private void streamDiagnosisResult(SseEmitter emitter, UUID createdBy) {
        OpsDiagnosisRun diagnosisRun = null;
        try {
            diagnosisRun = queueDiagnosisRun(createdBy);
            sendContent(emitter, "已创建诊断任务 " + diagnosisRun.getId() + "，正在排队...\n");
            waitForDiagnosisResult(emitter, diagnosisRun.getId());
        } catch (AiQuotaExhaustedException e) {
            sendError(emitter, e.getMessage(), AiQuotaExhaustedException.ERROR_CODE);
            emitter.complete();
        } catch (Exception e) {
            logger.error("AI Ops 异步诊断失败", e);
            sendError(emitter, "AI Ops 流程失败: " + e);
            emitter.complete();
        }
    }

    private void waitForDiagnosisResult(SseEmitter emitter, UUID diagnosisRunId) {
        long deadline = System.currentTimeMillis() + TimeUnit.MINUTES.toMillis(9);
        String lastStatus = null;
        while (System.currentTimeMillis() < deadline) {
            Optional<OpsDiagnosisRun> current = diagnosisPersistenceService.getRun(diagnosisRunId);
            if (current.isEmpty()) {
                sendError(emitter, "诊断任务不存在: " + diagnosisRunId);
                emitter.complete();
                return;
            }

            OpsDiagnosisRun run = current.get();
            String status = run.getStatus();
            if (!status.equals(lastStatus)) {
                sendDiagnosisStatus(emitter, run, status);
                lastStatus = status;
            }
            if (DiagnosisPersistenceService.STATUS_SUCCESS.equals(status)) {
                sendFinalReport(emitter, diagnosisRunId);
                return;
            }
            if (DiagnosisPersistenceService.STATUS_FAILED.equals(status)) {
                sendError(emitter, "AI Ops 流程失败: " + run.getErrorMessage());
                emitter.complete();
                return;
            }
            if (DiagnosisPersistenceService.STATUS_CANCELED.equals(status)) {
                sendError(emitter, "诊断任务已取消: " + run.getErrorMessage());
                emitter.complete();
                return;
            }
            if (!DiagnosisPersistenceService.STATUS_QUEUED.equals(status)
                    && !DiagnosisPersistenceService.STATUS_RUNNING.equals(status)
                    && !DiagnosisPersistenceService.STATUS_RETRYING.equals(status)) {
                sendError(emitter, "未知诊断任务状态: " + status);
                emitter.complete();
                return;
            }
            sleepBriefly();
        }
        sendError(emitter, "诊断任务等待超时");
        emitter.complete();
    }

    private void sendDiagnosisStatus(SseEmitter emitter, OpsDiagnosisRun run, String status) {
        if (DiagnosisPersistenceService.STATUS_RETRYING.equals(status)) {
            sendContent(emitter, "诊断任务状态: " + status + "，原因: " + run.getErrorMessage() + "\n");
            return;
        }
        sendContent(emitter, "诊断任务状态: " + status + "\n");
    }

    private void sendFinalReport(SseEmitter emitter, UUID diagnosisRunId) {
        Optional<OpsDiagnosisReport> report = diagnosisPersistenceService.getReport(diagnosisRunId);
        if (report.isEmpty() || report.get().getContent() == null || report.get().getContent().isBlank()) {
            sendError(emitter, "诊断任务成功但报告缺失");
            emitter.complete();
            return;
        }

        String finalReportText = report.get().getContent();
        sendContent(emitter, "\n\n" + "=".repeat(60) + "\n");
        sendContent(emitter, "📋 **告警分析报告**\n\n");
        int chunkSize = 50;
        for (int i = 0; i < finalReportText.length(); i += chunkSize) {
            sendContent(emitter, finalReportText.substring(i, Math.min(i + chunkSize, finalReportText.length())));
        }
        sendContent(emitter, "\n" + "=".repeat(60) + "\n\n");
        sendMessage(emitter, SseMessage.done());
        emitter.complete();
    }

    private void sleepBriefly() {
        try {
            Thread.sleep(500L);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("等待诊断任务状态被中断", e);
        }
    }

    private void sendContent(SseEmitter emitter, String content) {
        sendMessage(emitter, SseMessage.content(content));
    }

    private void sendError(SseEmitter emitter, String errorMessage) {
        sendMessage(emitter, SseMessage.error(errorMessage));
    }

    private void sendError(SseEmitter emitter, String errorMessage, String errorCode) {
        sendMessage(emitter, SseMessage.error(errorMessage, errorCode));
    }

    private void sendMessage(SseEmitter emitter, SseMessage message) {
        try {
            emitter.send(SseEmitter.event()
                    .name("message")
                    .data(message, MediaType.APPLICATION_JSON));
        } catch (IOException e) {
            logger.error("发送 AI Ops SSE 消息失败", e);
            emitter.complete();
            throw new IllegalStateException("发送 AI Ops SSE 消息失败", e);
        }
    }


    /**
     * 当前用户的会话列表
     */
    @GetMapping("/chat/sessions")
    public ResponseEntity<ApiResponse<List<SessionSummaryResponse>>> listSessions() {
        try {
            UUID currentUserId = authUserService.currentUserId().orElse(null);
            List<SessionSummaryResponse> sessions = chatSessionService.listSessions(currentUserId).stream()
                    .map(session -> new SessionSummaryResponse(
                            session.sessionId(),
                            session.title(),
                            session.messagePairCount(),
                            toEpochMillis(session.createdAt()),
                            toEpochMillis(session.updatedAt())
                    ))
                    .toList();
            return ResponseEntity.ok(ApiResponse.success(sessions));
        } catch (Exception e) {
            logger.error("获取会话列表失败", e);
            return ResponseEntity.ok(ApiResponse.error(e.getMessage()));
        }
    }

    /**
     * 当前用户仍在生成中的普通对话任务。
     * 前端切换会话或从后台标签页切回时，用它恢复每个会话的生成状态。
     */
    @GetMapping("/chat/tasks/active")
    public ResponseEntity<ApiResponse<List<ChatTaskRegistry.ChatTaskView>>> listActiveChatTasks() {
        try {
            UUID currentUserId = authUserService.currentUserId().orElse(null);
            return ResponseEntity.ok(ApiResponse.success(chatTaskRegistry.listActive(currentUserId)));
        } catch (Exception e) {
            logger.error("获取生成中的对话任务失败", e);
            return ResponseEntity.ok(ApiResponse.error(e.getMessage()));
        }
    }

    /**
     * 获取会话完整消息历史
     */
    @GetMapping("/chat/sessions/{sessionId}/messages")
    public ResponseEntity<ApiResponse<List<Map<String, String>>>> getSessionMessages(@PathVariable String sessionId) {
        try {
            UUID currentUserId = authUserService.currentUserId().orElse(null);
            return ResponseEntity.ok(ApiResponse.success(
                    chatSessionService.getHistory(sessionId, currentUserId)
            ));
        } catch (Exception e) {
            logger.error("获取会话消息失败", e);
            return ResponseEntity.ok(ApiResponse.error(e.getMessage()));
        }
    }

    /**
     * 删除整个会话
     */
    @DeleteMapping("/chat/sessions/{sessionId}")
    public ResponseEntity<ApiResponse<String>> deleteSession(@PathVariable String sessionId) {
        try {
            UUID currentUserId = authUserService.currentUserId().orElse(null);
            if (chatTaskRegistry.isActive(sessionId, currentUserId)) {
                return ResponseEntity.ok(ApiResponse.error("该对话正在生成回复，请完成后再删除"));
            }
            boolean deleted = chatSessionService.deleteSession(sessionId, currentUserId);
            if (!deleted) {
                return ResponseEntity.ok(ApiResponse.error("会话不存在或无权删除"));
            }
            return ResponseEntity.ok(ApiResponse.success("会话已删除"));
        } catch (Exception e) {
            logger.error("删除会话失败", e);
            return ResponseEntity.ok(ApiResponse.error(e.getMessage()));
        }
    }

    /**
     * 获取会话信息
     */
    @GetMapping("/chat/session/{sessionId}")
    public ResponseEntity<ApiResponse<SessionInfoResponse>> getSessionInfo(@PathVariable String sessionId) {
        try {
            UUID currentUserId = authUserService.currentUserId().orElse(null);
            logger.info("收到获取会话信息请求 - SessionId: {}", sessionId);

            return chatSessionService.getSessionInfo(sessionId, currentUserId)
                    .map(session -> {
                        SessionInfoResponse response = new SessionInfoResponse();
                        response.setSessionId(session.sessionId());
                        response.setMessagePairCount(session.messagePairCount());
                        response.setCreateTime(session.createdAt()
                                .atZone(ZoneId.systemDefault())
                                .toInstant()
                                .toEpochMilli());
                        return ResponseEntity.ok(ApiResponse.success(response));
                    })
                    .orElseGet(() -> ResponseEntity.ok(ApiResponse.error("会话不存在")));

        } catch (Exception e) {
            logger.error("获取会话信息失败", e);
            return ResponseEntity.ok(ApiResponse.error(e.getMessage()));
        }
    }

    /**
     * 聊天请求
     */
    @Setter
    @Getter
    public static class ChatRequest {
        @com.fasterxml.jackson.annotation.JsonProperty(value = "Id")
        @com.fasterxml.jackson.annotation.JsonAlias({"id", "ID"})
        private String Id;
        
        @com.fasterxml.jackson.annotation.JsonProperty(value = "Question")
        @com.fasterxml.jackson.annotation.JsonAlias({"question", "QUESTION"})
        private String Question;

        @com.fasterxml.jackson.annotation.JsonProperty(value = "Regenerate")
        @com.fasterxml.jackson.annotation.JsonAlias({"regenerate", "REGENERATE"})
        private boolean regenerate;

        @com.fasterxml.jackson.annotation.JsonProperty(value = "AssistantSequence")
        @com.fasterxml.jackson.annotation.JsonAlias({"assistantSequence", "ASSISTANT_SEQUENCE"})
        private Integer assistantSequence;

    }

    /**
     * 清空会话请求
     */
    @Setter
    @Getter
    public static class ClearRequest {
        @com.fasterxml.jackson.annotation.JsonProperty(value = "Id")
        @com.fasterxml.jackson.annotation.JsonAlias({"id", "ID"})
        private String Id;
    }

    // ==================== 内部类 ====================

    /**
     * 会话信息响应
     */
    @Setter
    @Getter
    public static class SessionInfoResponse {
        private String sessionId;
        private int messagePairCount;
        private long createTime;
    }

    @Setter
    @Getter
    public static class SessionSummaryResponse {
        private final String sessionId;
        private final String title;
        private final int messagePairCount;
        private final long createTime;
        private final long updateTime;

        public SessionSummaryResponse(
                String sessionId,
                String title,
                int messagePairCount,
                long createTime,
                long updateTime
        ) {
            this.sessionId = sessionId;
            this.title = title;
            this.messagePairCount = messagePairCount;
            this.createTime = createTime;
            this.updateTime = updateTime;
        }
    }

    /**
     * 统一聊天响应格式
     * 适用于所有普通返回模式的对话接口
     */
    @Setter
    @Getter
    public static class ChatResponse {
        private boolean success;
        private String answer;
        private String errorMessage;

        private String errorCode;

        public static ChatResponse success(String answer) {
            ChatResponse response = new ChatResponse();
            response.setSuccess(true);
            response.setAnswer(answer);
            return response;
        }

        public static ChatResponse error(String errorMessage) {
            return error(errorMessage, null);
        }

        public static ChatResponse error(String errorMessage, String errorCode) {
            ChatResponse response = new ChatResponse();
            response.setSuccess(false);
            response.setErrorMessage(errorMessage);
            response.setErrorCode(errorCode);
            return response;
        }
    }

    /**
     * 统一 SSE 流式消息格式
     * 适用于所有 SSE 流式返回模式的对话接口
     */
    @Setter
    @Getter
    public static class SseMessage {
        private String type;  // content: 内容块, error: 错误, done: 完成
        private String data;
        private String code;

        public static SseMessage content(String data) {
            SseMessage message = new SseMessage();
            message.setType("content");
            message.setData(data);
            return message;
        }

        public static SseMessage error(String errorMessage) {
            return error(errorMessage, null);
        }

        public static SseMessage error(String errorMessage, String errorCode) {
            SseMessage message = new SseMessage();
            message.setType("error");
            message.setData(errorMessage);
            message.setCode(errorCode);
            return message;
        }

        public static SseMessage done() {
            SseMessage message = new SseMessage();
            message.setType("done");
            message.setData(null);
            return message;
        }
    }


    @Getter
    @Setter
    public static class ApiResponse<T> {
        private int code;
        private String message;
        private T data;

        public static <T> ApiResponse<T> success(T data) {
            ApiResponse<T> response = new ApiResponse<>();
            response.setCode(200);
            response.setMessage("success");
            response.setData(data);
            return response;
        }

        public static <T> ApiResponse<T> error(String message) {
            ApiResponse<T> response = new ApiResponse<>();
            response.setCode(500);
            response.setMessage(message);
            return response;
        }

    }

    private static long toEpochMillis(java.time.LocalDateTime value) {
        return value.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
    }
}
