package com.soarer.alert.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * OpsDocument JPA 实体。
 */
@Getter
@Setter
@Entity
@Table(
        name = "ops_document",
        uniqueConstraints = {
                @UniqueConstraint(name = "ops_document_content_hash_uidx", columnNames = "content_hash")
        }
)
public class OpsDocument {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "file_name", nullable = false)
    private String fileName;

    @Column(name = "storage_key", unique = true)
    private String storageKey;

    @Column(name = "storage_url")
    private String storageUrl;

    @Column(name = "file_path")
    private String filePath;

    @Column(name = "content_hash", nullable = false)
    private String contentHash;

    @Column(name = "media_type", nullable = false)
    private String mediaType;

    @Column(name = "byte_size", nullable = false)
    private Long byteSize;

    @Column(nullable = false, length = 32)
    private String status;

    @Column(name = "document_version", nullable = false)
    private Integer documentVersion;

    @Column(name = "failure_reason", columnDefinition = "text")
    private String failureReason;

    @Column(name = "created_by")
    private UUID createdBy;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @Column(name = "indexed_at")
    private LocalDateTime indexedAt;

    @PrePersist
    void onCreate() {
        this.createdAt = LocalDateTime.now();
        this.updatedAt = this.createdAt;
        if (this.documentVersion == null) {
            this.documentVersion = 1;
        }
    }

    @PreUpdate
    void onUpdate() {
        this.updatedAt = LocalDateTime.now();
    }
}
