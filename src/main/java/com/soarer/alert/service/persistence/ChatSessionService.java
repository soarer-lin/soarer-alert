package com.soarer.alert.service.persistence;

import com.soarer.alert.entity.OpsChatMessage;
import com.soarer.alert.entity.OpsChatSession;
import com.soarer.alert.repository.OpsChatMessageRepository;
import com.soarer.alert.repository.OpsChatSessionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.time.ZoneId;

@Service
public class ChatSessionService {

    private static final int MODEL_CONTEXT_PAIRS = 6;
    private static final int MODEL_CONTEXT_MESSAGES = MODEL_CONTEXT_PAIRS * 2;
    private static final int SESSION_TITLE_LENGTH = 40;

    private final OpsChatSessionRepository sessionRepository;
    private final OpsChatMessageRepository messageRepository;

    public ChatSessionService(
            OpsChatSessionRepository sessionRepository,
            OpsChatMessageRepository messageRepository
    ) {
        this.sessionRepository = sessionRepository;
        this.messageRepository = messageRepository;
    }

    @Transactional(readOnly = true)
    public List<Map<String, String>> getHistory(String sessionId) {
        return getHistory(sessionId, null);
    }

    @Transactional(readOnly = true)
    public List<Map<String, String>> getHistory(String sessionId, UUID currentUserId) {
        String normalizedSessionId = normalizeSessionId(sessionId);
        if (!canAccess(normalizedSessionId, currentUserId)) {
            return List.of();
        }
        return messageRepository.findBySessionIdOrderBySequenceAsc(normalizedSessionId).stream()
                .map(this::toHistoryMessage)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<Map<String, String>> getRecentHistory(String sessionId, UUID currentUserId) {
        List<Map<String, String>> history = getHistory(sessionId, currentUserId);
        if (history.size() <= MODEL_CONTEXT_MESSAGES) {
            return history;
        }
        return history.subList(history.size() - MODEL_CONTEXT_MESSAGES, history.size());
    }

    @Transactional(readOnly = true)
    public List<Map<String, String>> getRecentHistoryBefore(
            String sessionId,
            UUID currentUserId,
            int sequenceExclusive
    ) {
        if (sequenceExclusive <= 0) {
            return List.of();
        }

        String normalizedSessionId = normalizeSessionId(sessionId);
        if (!canAccess(normalizedSessionId, currentUserId)) {
            return List.of();
        }

        List<Map<String, String>> history = messageRepository
                .findBySessionIdOrderBySequenceAsc(normalizedSessionId)
                .stream()
                .filter(message -> message.getSequence() < sequenceExclusive)
                .map(this::toHistoryMessage)
                .toList();

        if (history.size() <= MODEL_CONTEXT_MESSAGES) {
            return history;
        }
        return history.subList(history.size() - MODEL_CONTEXT_MESSAGES, history.size());
    }

    @Transactional(readOnly = true)
    public List<SessionSummary> listSessions(UUID currentUserId) {
        if (currentUserId == null) {
            return List.of();
        }
        return sessionRepository.findByUserIdOrderByUpdatedAtDesc(currentUserId).stream()
                .map(session -> {
                    List<OpsChatMessage> messages =
                            messageRepository.findBySessionIdOrderBySequenceAsc(session.getSessionId());
                    return new SessionSummary(
                            session.getSessionId(),
                            sessionTitle(messages),
                            messages.size() / 2,
                            session.getCreatedAt(),
                            session.getUpdatedAt()
                    );
                })
                .toList();
    }

    @Transactional
    public void addExchange(String sessionId, String question, String answer) {
        addExchange(sessionId, null, question, answer);
    }

    @Transactional
    public void addExchange(String sessionId, UUID currentUserId, String question, String answer) {
        String normalizedSessionId = normalizeSessionId(sessionId);
        OpsChatSession session = ensureSession(normalizedSessionId, currentUserId);
        sessionRepository.save(session);

        List<OpsChatMessage> existingMessages =
                messageRepository.findBySessionIdOrderBySequenceAsc(normalizedSessionId);
        int nextSequence = existingMessages.isEmpty()
                ? 0
                : existingMessages.get(existingMessages.size() - 1).getSequence() + 1;

        messageRepository.save(message(normalizedSessionId, "user", question, nextSequence));
        messageRepository.save(message(normalizedSessionId, "assistant", answer, nextSequence + 1));
    }

    @Transactional
    public int addUserMessage(String sessionId, UUID currentUserId, String question) {
        String normalizedSessionId = normalizeSessionId(sessionId);
        OpsChatSession session = ensureSession(normalizedSessionId, currentUserId);
        touch(session);
        sessionRepository.save(session);

        int nextSequence = nextSequence(normalizedSessionId);
        messageRepository.save(message(normalizedSessionId, "user", question, nextSequence));
        return nextSequence;
    }

    @Transactional
    public int addAssistantAnswer(String sessionId, UUID currentUserId, String answer) {
        String normalizedSessionId = normalizeSessionId(sessionId);
        OpsChatSession session = ensureSession(normalizedSessionId, currentUserId);
        touch(session);
        sessionRepository.save(session);

        int nextSequence = nextSequence(normalizedSessionId);
        messageRepository.save(message(normalizedSessionId, "assistant", answer, nextSequence));
        return nextSequence;
    }

    @Transactional
    public void replaceAssistantAnswer(
            String sessionId,
            UUID currentUserId,
            int assistantSequence,
            String answer
    ) {
        if (assistantSequence < 1 || assistantSequence % 2 == 0) {
            throw new IllegalArgumentException("无效的助手消息序号");
        }

        String normalizedSessionId = normalizeSessionId(sessionId);
        if (!canAccess(normalizedSessionId, currentUserId)) {
            throw new IllegalArgumentException("无权访问该会话");
        }

        OpsChatMessage target = messageRepository
                .findBySessionIdOrderBySequenceAsc(normalizedSessionId)
                .stream()
                .filter(message -> assistantSequence == message.getSequence())
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("找不到要重新生成的助手消息"));

        if (!"assistant".equals(target.getRole())) {
            throw new IllegalArgumentException("目标消息不是助手回复");
        }

        messageRepository.deleteAfterSequence(normalizedSessionId, assistantSequence);
        target.setContent(answer == null ? "" : answer);
        target.setCreatedAt(java.time.LocalDateTime.now());
        messageRepository.save(target);
        sessionRepository.findById(normalizedSessionId).ifPresent(session -> {
            session.setUpdatedAt(java.time.LocalDateTime.now());
            sessionRepository.save(session);
        });
    }

    @Transactional
    public boolean clearHistory(String sessionId) {
        return clearHistory(sessionId, null);
    }

    @Transactional
    public boolean clearHistory(String sessionId, UUID currentUserId) {
        if (sessionId == null || sessionId.isBlank()) {
            return false;
        }
        if (!canAccess(sessionId, currentUserId)) {
            return false;
        }
        messageRepository.deleteBySessionId(sessionId);
        return true;
    }

    @Transactional
    public boolean deleteSession(String sessionId, UUID currentUserId) {
        if (sessionId == null || sessionId.isBlank()) {
            return false;
        }
        String normalizedSessionId = sessionId.trim();
        if (!canAccess(normalizedSessionId, currentUserId)) {
            return false;
        }
        messageRepository.deleteBySessionId(normalizedSessionId);
        sessionRepository.deleteById(normalizedSessionId);
        return true;
    }

    @Transactional(readOnly = true)
    public Optional<SessionInfo> getSessionInfo(String sessionId) {
        return getSessionInfo(sessionId, null);
    }

    @Transactional(readOnly = true)
    public Optional<SessionInfo> getSessionInfo(String sessionId, UUID currentUserId) {
        if (sessionId == null || sessionId.isBlank()) {
            return Optional.empty();
        }
        if (!canAccess(sessionId, currentUserId)) {
            return Optional.empty();
        }
        return sessionRepository.findById(sessionId)
                .map(session -> new SessionInfo(
                        session.getSessionId(),
                        messageRepository.findBySessionIdOrderBySequenceAsc(sessionId).size() / 2,
                        session.getCreatedAt()
                ));
    }

    private OpsChatSession ensureSession(String sessionId, UUID currentUserId) {
        OpsChatSession session = sessionRepository.findById(sessionId).orElseGet(OpsChatSession::new);
        if (session.getSessionId() == null) {
            session.setSessionId(sessionId);
            session.setUserId(currentUserId);
        } else if (currentUserId != null && !currentUserId.equals(session.getUserId())) {
            throw new IllegalArgumentException("无权访问该会话");
        }
        return session;
    }

    private boolean canAccess(String sessionId, UUID currentUserId) {
        if (currentUserId == null) {
            return sessionRepository.existsById(sessionId);
        }
        return sessionRepository.findById(sessionId)
                .map(session -> currentUserId.equals(session.getUserId()))
                .orElse(false);
    }

    private OpsChatMessage message(String sessionId, String role, String content, int sequence) {
        OpsChatMessage message = new OpsChatMessage();
        message.setSessionId(sessionId);
        message.setRole(role);
        message.setContent(content == null ? "" : content);
        message.setSequence(sequence);
        return message;
    }

    private int nextSequence(String sessionId) {
        List<OpsChatMessage> existingMessages = messageRepository.findBySessionIdOrderBySequenceAsc(sessionId);
        return existingMessages.isEmpty()
                ? 0
                : existingMessages.get(existingMessages.size() - 1).getSequence() + 1;
    }

    private void touch(OpsChatSession session) {
        session.setUpdatedAt(java.time.LocalDateTime.now());
    }

    private Map<String, String> toHistoryMessage(OpsChatMessage message) {
        Map<String, String> historyMessage = new LinkedHashMap<>();
        historyMessage.put("role", message.getRole());
        historyMessage.put("content", message.getContent());
        if (message.getCreatedAt() != null) {
            historyMessage.put(
                    "createdAt",
                    String.valueOf(message.getCreatedAt()
                            .atZone(ZoneId.systemDefault())
                            .toInstant()
                            .toEpochMilli())
            );
        }
        return historyMessage;
    }

    private String normalizeSessionId(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return UUID.randomUUID().toString();
        }
        return sessionId.trim();
    }

    private String sessionTitle(List<OpsChatMessage> messages) {
        return messages.stream()
                .filter(message -> "user".equals(message.getRole()))
                .findFirst()
                .map(message -> truncate(message.getContent(), SESSION_TITLE_LENGTH))
                .orElse("新对话");
    }

    private String truncate(String value, int maxLength) {
        String normalized = value == null ? "" : value.trim();
        return normalized.length() <= maxLength ? normalized : normalized.substring(0, maxLength);
    }

    public record SessionInfo(String sessionId, int messagePairCount, java.time.LocalDateTime createdAt) {
    }

    public record SessionSummary(
            String sessionId,
            String title,
            int messagePairCount,
            java.time.LocalDateTime createdAt,
            java.time.LocalDateTime updatedAt
    ) {
    }
}
