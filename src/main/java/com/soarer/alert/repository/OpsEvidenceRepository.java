package com.soarer.alert.repository;

import com.soarer.alert.entity.OpsEvidence;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface OpsEvidenceRepository extends JpaRepository<OpsEvidence, UUID> {
    List<OpsEvidence> findByDiagnosisRunIdOrderByCreatedAtAsc(UUID diagnosisRunId);
}
