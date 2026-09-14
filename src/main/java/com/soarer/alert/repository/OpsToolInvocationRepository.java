package com.soarer.alert.repository;

import com.soarer.alert.entity.OpsToolInvocation;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface OpsToolInvocationRepository extends JpaRepository<OpsToolInvocation, UUID> {
    List<OpsToolInvocation> findByDiagnosisRunIdOrderByStartedAtAsc(UUID diagnosisRunId);
}
