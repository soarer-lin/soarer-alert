package com.soarer.alert.repository;

import com.soarer.alert.entity.AuthUser;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

/**
 * 访问 AuthUser 数据的 Spring Data 接口。
 */
public interface AuthUserRepository extends JpaRepository<AuthUser, UUID> {
    Optional<AuthUser> findByUsername(String username);

    boolean existsByUsername(String username);

    Page<AuthUser> findAllByOrderByCreatedAtDesc(Pageable pageable);

    long countByRoleAndStatus(String role, String status);

    Optional<AuthUser> findFirstByDemoLoginEnabledTrueAndStatusOrderByCreatedAtDesc(String status);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
            UPDATE auth_user
            SET demo_login_enabled = FALSE,
                demo_password_hash = NULL,
                updated_at = now()
            WHERE demo_login_enabled = TRUE
            """, nativeQuery = true)
    int clearDemoLogin();

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
            UPDATE auth_user
            SET ai_quota_used = ai_quota_used + 1,
                updated_at = now()
            WHERE id = :userId
              AND (ai_quota_limit IS NULL OR ai_quota_used < ai_quota_limit)
            """, nativeQuery = true)
    int consumeAiQuota(@Param("userId") UUID userId);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
            UPDATE auth_user
            SET ai_quota_used = GREATEST(ai_quota_used - 1, 0),
                updated_at = now()
            WHERE id = :userId
            """, nativeQuery = true)
    int refundAiQuota(@Param("userId") UUID userId);
}
