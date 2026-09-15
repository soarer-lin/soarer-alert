package com.soarer.alert.repository;

import com.soarer.alert.entity.OpsDiagnosisReport;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

/**
 * 访问 OpsDiagnosisReport 数据的 Spring Data 接口。
 */
public interface OpsDiagnosisReportRepository extends JpaRepository<OpsDiagnosisReport, UUID> {
    Optional<OpsDiagnosisReport> findByDiagnosisRunId(UUID diagnosisRunId);

    long countByStatus(String status);
}
