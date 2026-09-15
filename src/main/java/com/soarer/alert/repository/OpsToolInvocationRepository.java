package com.soarer.alert.repository;

import com.soarer.alert.entity.OpsToolInvocation;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

/**
 * 访问 OpsToolInvocation 数据的 Spring Data 接口。
 */
public interface OpsToolInvocationRepository extends JpaRepository<OpsToolInvocation, UUID> {
    List<OpsToolInvocation> findByDiagnosisRunIdOrderByStartedAtAsc(UUID diagnosisRunId);
}
