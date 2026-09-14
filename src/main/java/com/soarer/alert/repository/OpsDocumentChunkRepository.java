package com.soarer.alert.repository;

import com.soarer.alert.entity.OpsDocumentChunk;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface OpsDocumentChunkRepository extends JpaRepository<OpsDocumentChunk, UUID> {
    List<OpsDocumentChunk> findByDocumentIdOrderByChunkIndexAsc(UUID documentId);

    long countByDocumentId(UUID documentId);

    @Modifying
    @Query("DELETE FROM OpsDocumentChunk c WHERE c.documentId = :documentId")
    int deleteByDocumentId(@Param("documentId") UUID documentId);
}
