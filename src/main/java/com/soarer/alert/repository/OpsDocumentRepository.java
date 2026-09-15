package com.soarer.alert.repository;

import com.soarer.alert.entity.OpsDocument;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

/**
 * 访问 OpsDocument 数据的 Spring Data 接口。
 */
public interface OpsDocumentRepository extends JpaRepository<OpsDocument, UUID> {
    Optional<OpsDocument> findByContentHash(String contentHash);

    Optional<OpsDocument> findByStorageKey(String storageKey);

    Page<OpsDocument> findAllByOrderByCreatedAtDesc(Pageable pageable);
}
