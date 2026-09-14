package com.soarer.alert.service.persistence;

import com.soarer.alert.entity.OpsChatMessage;
import com.soarer.alert.entity.OpsChatSession;
import com.soarer.alert.repository.OpsChatMessageRepository;
import com.soarer.alert.repository.OpsChatSessionRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ChatSessionServiceTest {

    private final OpsChatSessionRepository sessionRepository = mock(OpsChatSessionRepository.class);
    private final OpsChatMessageRepository messageRepository = mock(OpsChatMessageRepository.class);
    private final ChatSessionService service =
            new ChatSessionService(sessionRepository, messageRepository);

    @Test
    void addExchangeSavesSessionBeforeMessagesAndContinuesSequence() {
        when(sessionRepository.findById("session-1")).thenReturn(Optional.empty());
        when(messageRepository.findBySessionIdOrderBySequenceAsc("session-1"))
                .thenReturn(List.of(message("session-1", "user", 0), message("session-1", "assistant", 1)));

        service.addExchange(" session-1 ", "question", "answer");

        InOrder order = inOrder(sessionRepository, messageRepository);
        order.verify(sessionRepository).save(any(OpsChatSession.class));
        order.verify(messageRepository, org.mockito.Mockito.times(2)).save(any(OpsChatMessage.class));

        ArgumentCaptor<OpsChatSession> sessionCaptor = ArgumentCaptor.forClass(OpsChatSession.class);
        verify(sessionRepository).save(sessionCaptor.capture());
        assertThat(sessionCaptor.getValue().getSessionId()).isEqualTo("session-1");

        ArgumentCaptor<OpsChatMessage> messageCaptor = ArgumentCaptor.forClass(OpsChatMessage.class);
        verify(messageRepository, org.mockito.Mockito.times(2)).save(messageCaptor.capture());
        List<OpsChatMessage> savedMessages = messageCaptor.getAllValues();
        assertThat(savedMessages)
                .extracting(OpsChatMessage::getRole, OpsChatMessage::getSequence)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("user", 2),
                        org.assertj.core.groups.Tuple.tuple("assistant", 3)
                );
        verify(messageRepository, never()).deleteBeforeSequence(anyString(), anyInt());
    }

    @Test
    void addExchangeKeepsCompleteHistoryForEverySession() {
        when(sessionRepository.findById("session-1")).thenReturn(Optional.of(session("session-1")));
        List<OpsChatMessage> existingMessages = new java.util.ArrayList<>();
        for (int sequence = 0; sequence < 12; sequence++) {
            existingMessages.add(message("session-1", sequence % 2 == 0 ? "user" : "assistant", sequence));
        }
        when(messageRepository.findBySessionIdOrderBySequenceAsc("session-1")).thenReturn(existingMessages);

        service.addExchange("session-1", "question", "answer");

        verify(messageRepository, never()).deleteBeforeSequence(anyString(), anyInt());
        ArgumentCaptor<OpsChatMessage> messageCaptor = ArgumentCaptor.forClass(OpsChatMessage.class);
        verify(messageRepository, org.mockito.Mockito.times(2)).save(messageCaptor.capture());
        assertThat(messageCaptor.getAllValues())
                .extracting(OpsChatMessage::getSequence)
                .containsExactly(12, 13);
    }

    @Test
    void clearHistoryDeletesMessagesOnlyWhenSessionExists() {
        when(sessionRepository.existsById("session-1")).thenReturn(true);

        boolean cleared = service.clearHistory("session-1");

        assertThat(cleared).isTrue();
        verify(messageRepository).deleteBySessionId("session-1");
    }

    @Test
    void getHistoryReturnsMessagesInStoredSequence() {
        when(sessionRepository.existsById("session-1")).thenReturn(true);
        when(messageRepository.findBySessionIdOrderBySequenceAsc("session-1"))
                .thenReturn(List.of(message("session-1", "user", 0), message("session-1", "assistant", 1)));

        List<java.util.Map<String, String>> history = service.getHistory(" session-1 ");

        assertThat(history)
                .extracting(message -> message.get("role"), message -> message.get("content"))
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("user", "user-0"),
                        org.assertj.core.groups.Tuple.tuple("assistant", "assistant-1")
                );
    }

    @Test
    void getRecentHistoryUsesOnlyTheLatestTwelveMessages() {
        UUID userId = UUID.randomUUID();
        OpsChatSession session = session("session-1");
        session.setUserId(userId);
        when(sessionRepository.findById("session-1")).thenReturn(Optional.of(session));
        List<OpsChatMessage> messages = new java.util.ArrayList<>();
        for (int sequence = 0; sequence < 16; sequence++) {
            messages.add(message("session-1", sequence % 2 == 0 ? "user" : "assistant", sequence));
        }
        when(messageRepository.findBySessionIdOrderBySequenceAsc("session-1")).thenReturn(messages);

        List<java.util.Map<String, String>> recentHistory = service.getRecentHistory("session-1", userId);

        assertThat(recentHistory)
                .extracting(message -> message.get("content"))
                .containsExactly("user-4", "assistant-5", "user-6", "assistant-7", "user-8",
                        "assistant-9", "user-10", "assistant-11", "user-12", "assistant-13",
                        "user-14", "assistant-15");
    }

    @Test
    void replaceAssistantAnswerDeletesLaterBranchAndReusesTargetSequence() {
        UUID userId = UUID.randomUUID();
        OpsChatSession session = session("session-1");
        session.setUserId(userId);
        when(sessionRepository.findById("session-1")).thenReturn(Optional.of(session));
        when(messageRepository.findBySessionIdOrderBySequenceAsc("session-1"))
                .thenReturn(List.of(
                        message("session-1", "user", 0),
                        message("session-1", "assistant", 1),
                        message("session-1", "user", 2),
                        message("session-1", "assistant", 3)
                ));

        service.replaceAssistantAnswer("session-1", userId, 1, "new-answer");

        verify(messageRepository).deleteAfterSequence("session-1", 1);
        ArgumentCaptor<OpsChatMessage> messageCaptor = ArgumentCaptor.forClass(OpsChatMessage.class);
        verify(messageRepository).save(messageCaptor.capture());
        assertThat(messageCaptor.getValue().getSequence()).isEqualTo(1);
        assertThat(messageCaptor.getValue().getRole()).isEqualTo("assistant");
        assertThat(messageCaptor.getValue().getContent()).isEqualTo("new-answer");
        verify(sessionRepository).save(session);
    }

    @Test
    void listSessionsOnlyReturnsSessionsOwnedByCurrentUser() {
        UUID userId = UUID.randomUUID();
        OpsChatSession ownedSession = session("owned-session");
        ownedSession.setUserId(userId);
        when(sessionRepository.findByUserIdOrderByUpdatedAtDesc(userId)).thenReturn(List.of(ownedSession));
        when(messageRepository.findBySessionIdOrderBySequenceAsc("owned-session"))
                .thenReturn(List.of(message("owned-session", "user", 0), message("owned-session", "assistant", 1)));

        List<ChatSessionService.SessionSummary> sessions = service.listSessions(userId);

        assertThat(sessions)
                .hasSize(1)
                .first()
                .extracting(ChatSessionService.SessionSummary::sessionId, ChatSessionService.SessionSummary::title)
                .containsExactly("owned-session", "user-0");
    }

    @Test
    void getHistoryDoesNotLeakAnotherUsersSession() {
        UUID ownerId = UUID.randomUUID();
        UUID otherUserId = UUID.randomUUID();
        OpsChatSession session = session("session-1");
        session.setUserId(ownerId);
        when(sessionRepository.findById("session-1")).thenReturn(Optional.of(session));

        List<java.util.Map<String, String>> history = service.getHistory("session-1", otherUserId);

        assertThat(history).isEmpty();
        verify(messageRepository, never()).findBySessionIdOrderBySequenceAsc(anyString());
    }

    @Test
    void deleteSessionOnlyDeletesTheOwnersSession() {
        UUID ownerId = UUID.randomUUID();
        UUID otherUserId = UUID.randomUUID();
        OpsChatSession session = session("session-1");
        session.setUserId(ownerId);
        when(sessionRepository.findById("session-1")).thenReturn(Optional.of(session));

        boolean deleted = service.deleteSession("session-1", otherUserId);

        assertThat(deleted).isFalse();
        verify(messageRepository, never()).deleteBySessionId(anyString());
        verify(sessionRepository, never()).deleteById(anyString());
    }

    private OpsChatMessage message(String sessionId, String role, int sequence) {
        OpsChatMessage message = new OpsChatMessage();
        message.setSessionId(sessionId);
        message.setRole(role);
        message.setContent(role + "-" + sequence);
        message.setSequence(sequence);
        return message;
    }

    private OpsChatSession session(String sessionId) {
        OpsChatSession session = new OpsChatSession();
        session.setSessionId(sessionId);
        return session;
    }
}
