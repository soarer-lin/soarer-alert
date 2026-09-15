package com.soarer.alert.repository;

import com.soarer.alert.entity.OpsAgentStep;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 访问 OpsAgentStep 数据的 Spring Data 接口。
 */
public interface OpsAgentStepRepository extends JpaRepository<OpsAgentStep, UUID> {
    @Query("select max(s.stepIndex) from OpsAgentStep s where s.diagnosisRunId = ?1")
    Optional<Integer> findMaxStepIndexByDiagnosisRunId(UUID diagnosisRunId);

    List<OpsAgentStep> findByDiagnosisRunIdOrderByStepIndexAsc(UUID diagnosisRunId);
}
