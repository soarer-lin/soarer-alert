package com.soarer.alert.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.UUID;

@Getter
@Setter
@Entity
@Table(name = "ops_alert")
public class OpsAlert {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "diagnosis_run_id")
    private UUID diagnosisRunId;

    @Column(name = "alert_name", nullable = false)
    private String alertName;

    @Column(length = 32)
    private String severity;

    @Column(name = "service_name")
    private String serviceName;

    @Column(length = 64)
    private String environment;

    @Column(length = 32)
    private String status;

    @Column(name = "first_triggered_at")
    private LocalDateTime firstTriggeredAt;

    @Column(name = "last_triggered_at")
    private LocalDateTime lastTriggeredAt;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @PrePersist
    void onCreate() {
        this.createdAt = LocalDateTime.now();
    }
}
