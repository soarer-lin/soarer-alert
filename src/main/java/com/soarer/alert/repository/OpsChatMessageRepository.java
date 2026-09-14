package com.soarer.alert.repository;

import com.soarer.alert.entity.OpsChatMessage;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface OpsChatMessageRepository extends JpaRepository<OpsChatMessage, java.util.UUID> {
    List<OpsChatMessage> findBySessionIdOrderBySequenceAsc(String sessionId);

    Optional<OpsChatMessage> findFirstBySessionIdAndRoleOrderBySequenceAsc(String sessionId, String role);

    @Modifying
    @Query("DELETE FROM OpsChatMessage m WHERE m.sessionId = :sessionId")
    int deleteBySessionId(@Param("sessionId") String sessionId);

    @Modifying
    @Query("DELETE FROM OpsChatMessage m WHERE m.sessionId = :sessionId AND m.sequence < :sequence")
    int deleteBeforeSequence(@Param("sessionId") String sessionId, @Param("sequence") int sequence);

    @Modifying
    @Query("DELETE FROM OpsChatMessage m WHERE m.sessionId = :sessionId AND m.sequence > :sequence")
    int deleteAfterSequence(@Param("sessionId") String sessionId, @Param("sequence") int sequence);
}
