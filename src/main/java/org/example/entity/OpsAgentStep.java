package org.example.entity;

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

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.UUID;

@Getter
@Setter
@Entity
@Table(
        name = "ops_agent_step",
        uniqueConstraints = {
                @UniqueConstraint(name = "ops_agent_step_unique_run_index", columnNames = {"diagnosis_run_id", "step_index"})
        }
)
public class OpsAgentStep {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "diagnosis_run_id", nullable = false)
    private UUID diagnosisRunId;

    @Column(name = "step_index", nullable = false)
    private Integer stepIndex;

    @Column(name = "agent_name", nullable = false)
    private String agentName;

    @Column(name = "step_type", nullable = false, length = 32)
    private String stepType;

    @Column(columnDefinition = "text")
    private String instruction;

    @Column(name = "input_text", columnDefinition = "text")
    private String inputText;

    @Column(name = "output_text", columnDefinition = "text")
    private String outputText;

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
