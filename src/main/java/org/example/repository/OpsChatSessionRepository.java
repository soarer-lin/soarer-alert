package org.example.repository;

import org.example.entity.OpsChatSession;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface OpsChatSessionRepository extends JpaRepository<OpsChatSession, String> {
    List<OpsChatSession> findByUserIdOrderByUpdatedAtDesc(UUID userId);
}
