package org.example.repository;

import org.example.entity.OpsDocument;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface OpsDocumentRepository extends JpaRepository<OpsDocument, UUID> {
    Optional<OpsDocument> findByContentHash(String contentHash);

    Optional<OpsDocument> findByStorageKey(String storageKey);

    Page<OpsDocument> findAllByOrderByCreatedAtDesc(Pageable pageable);
}
