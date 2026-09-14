package org.example.repository;

import org.example.entity.OpsAlert;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface OpsAlertRepository extends JpaRepository<OpsAlert, UUID> {
    Page<OpsAlert> findByStatus(String status, Pageable pageable);

    Page<OpsAlert> findAllByOrderByLastTriggeredAtDesc(Pageable pageable);
}
