package com.soarer.alert.repository;

import com.soarer.alert.entity.OpsChatSession;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

/**
 * 访问 OpsChatSession 数据的 Spring Data 接口。
 */
public interface OpsChatSessionRepository extends JpaRepository<OpsChatSession, String> {
    List<OpsChatSession> findByUserIdOrderByUpdatedAtDesc(UUID userId);
}
