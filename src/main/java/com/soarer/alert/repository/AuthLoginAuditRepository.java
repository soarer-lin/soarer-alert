package com.soarer.alert.repository;

import com.soarer.alert.entity.AuthLoginAudit;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AuthLoginAuditRepository extends JpaRepository<AuthLoginAudit, Long> {
    Page<AuthLoginAudit> findAllByOrderByCreatedAtDesc(Pageable pageable);
}
