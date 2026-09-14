package org.example.service.chat;

import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tracks in-flight chat generations so they can survive browser navigation.
 */
@Service
public class ChatTaskRegistry {

    private final Map<String, ChatTask> tasks = new ConcurrentHashMap<>();

    public ChatTask start(String sessionId, UUID userId, String question, boolean regenerate) {
        String key = key(userId, sessionId);
        ChatTask created = new ChatTask(sessionId, userId, question, regenerate);
        ChatTask previous = tasks.compute(key, (ignored, existing) -> {
            if (existing != null && existing.isRunning()) {
                return existing;
            }
            return created;
        });
        if (previous != created) {
            throw new IllegalStateException("该对话正在生成回复，请稍候");
        }
        return created;
    }

    public void complete(ChatTask task) {
        task.complete();
        tasks.remove(key(task.userId, task.sessionId), task);
    }

    public void fail(ChatTask task) {
        task.fail();
        tasks.remove(key(task.userId, task.sessionId), task);
    }

    public List<ChatTaskView> listActive(UUID userId) {
        if (userId == null) {
            return List.of();
        }
        return tasks.values().stream()
                .filter(task -> userId.equals(task.userId) && task.isRunning())
                .map(ChatTask::toView)
                .toList();
    }

    public boolean isActive(String sessionId, UUID userId) {
        if (sessionId == null || userId == null) {
            return false;
        }
        ChatTask task = tasks.get(key(userId, sessionId));
        return task != null && task.isRunning();
    }

    private String key(UUID userId, String sessionId) {
        return userId + ":" + sessionId;
    }

    public static final class ChatTask {
        private final String sessionId;
        private final UUID userId;
        private final String question;
        private final boolean regenerate;
        private final Instant startedAt = Instant.now();
        private final StringBuilder answer = new StringBuilder();
        private volatile boolean running = true;

        private ChatTask(String sessionId, UUID userId, String question, boolean regenerate) {
            this.sessionId = sessionId;
            this.userId = userId;
            this.question = question;
            this.regenerate = regenerate;
        }

        public synchronized void append(String content) {
            if (running && content != null && !content.isEmpty()) {
                answer.append(content);
            }
        }

        private void complete() {
            running = false;
        }

        private void fail() {
            running = false;
        }

        public boolean isRunning() {
            return running;
        }

        private ChatTaskView toView() {
            return new ChatTaskView(
                    sessionId,
                    question,
                    snapshotAnswer(),
                    regenerate,
                    startedAt.toEpochMilli()
            );
        }

        private synchronized String snapshotAnswer() {
            return answer.toString();
        }
    }

    public record ChatTaskView(
            String sessionId,
            String question,
            String partialAnswer,
            boolean regenerate,
            long startedAt
    ) {
    }
}
