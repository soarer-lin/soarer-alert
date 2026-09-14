package org.example.repository;

import org.example.entity.OpsDiagnosisRun;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface OpsDiagnosisRunRepository extends JpaRepository<OpsDiagnosisRun, UUID> {
    Page<OpsDiagnosisRun> findByStatus(String status, Pageable pageable);

    long countByStatus(String status);
}
