package com.soarer.alert.repository;

import com.soarer.alert.entity.OpsDiagnosisRun;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

/**
 * 访问 OpsDiagnosisRun 数据的 Spring Data 接口。
 */
public interface OpsDiagnosisRunRepository extends JpaRepository<OpsDiagnosisRun, UUID> {
    Page<OpsDiagnosisRun> findByStatus(String status, Pageable pageable);

    long countByStatus(String status);
}
