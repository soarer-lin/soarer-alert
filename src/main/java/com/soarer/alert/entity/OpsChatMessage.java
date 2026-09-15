package com.soarer.alert.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * OpsChatMessage JPA 实体。
 */
@Getter
@Setter
@Entity
@Table(
        name = "ops_chat_message",
        uniqueConstraints = {
                @UniqueConstraint(name = "ops_chat_message_unique_sequence", columnNames = {"session_id", "sequence"})
        }
)
public class OpsChatMessage {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "session_id", nullable = false)
    private String sessionId;

    @Column(nullable = false, length = 16)
    private String role;

    @Column(nullable = false, columnDefinition = "text")
    private String content;

    @Column(nullable = false)
    private Integer sequence;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @PrePersist
    void onCreate() {
        this.createdAt = LocalDateTime.now();
    }
}
