package com.soarer.alert.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * OpsDiagnosisRun JPA 实体。
 */
@Getter
@Setter
@Entity
@Table(name = "ops_diagnosis_run")
public class OpsDiagnosisRun {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "request_text", nullable = false, columnDefinition = "text")
    private String requestText;

    @Column(nullable = false, length = 32)
    private String status;

    @Column(name = "started_at", nullable = false)
    private LocalDateTime startedAt;

    @Column(name = "completed_at")
    private LocalDateTime completedAt;

    @Column(name = "duration_ms")
    private Long durationMs;

    @Column(name = "error_message", columnDefinition = "text")
    private String errorMessage;

    @Column(name = "created_by")
    private UUID createdBy;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    void onCreate() {
        this.createdAt = LocalDateTime.now();
        this.updatedAt = this.createdAt;
        if (this.startedAt == null) {
            this.startedAt = this.createdAt;
        }
    }

    @PreUpdate
    void onUpdate() {
        this.updatedAt = LocalDateTime.now();
    }

    public void complete(String status) {
        this.status = status;
        this.completedAt = LocalDateTime.now();
        this.durationMs = Duration.between(this.startedAt, this.completedAt).toMillis();
    }
}
