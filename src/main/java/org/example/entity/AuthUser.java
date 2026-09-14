package org.example.entity;

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

import java.time.LocalDateTime;
import java.util.UUID;

@Getter
@Setter
@Entity
@Table(name = "auth_user")
public class AuthUser {

    public static final String ROLE_ADMIN = "ADMIN";
    public static final String ROLE_OPS = "OPS";
    public static final String STATUS_ACTIVE = "ACTIVE";
    public static final String STATUS_DISABLED = "DISABLED";

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false, unique = true, length = 254)
    private String username;

    @Column(name = "password_hash", nullable = false)
    private String passwordHash;

    @Column(name = "display_name", length = 100)
    private String displayName;

    @Column(nullable = false, length = 20)
    private String role;

    @Column(nullable = false, length = 20)
    private String status;

    @Column(name = "must_change_password", nullable = false)
    private Boolean mustChangePassword;

    @Column(name = "password_change_locked", nullable = false)
    private Boolean passwordChangeLocked;

    @Column(name = "demo_login_enabled", nullable = false)
    private Boolean demoLoginEnabled;

    @Column(name = "demo_password_hash", length = 100)
    private String demoPasswordHash;

    @Column(name = "failed_login_count", nullable = false)
    private Integer failedLoginCount;

    @Column(name = "ai_quota_limit")
    private Integer aiQuotaLimit;

    @Column(name = "ai_quota_used", nullable = false)
    private Integer aiQuotaUsed;

    @Column(name = "locked_until")
    private LocalDateTime lockedUntil;

    @Column(name = "password_updated_at")
    private LocalDateTime passwordUpdatedAt;

    @Column(name = "last_login_at")
    private LocalDateTime lastLoginAt;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    void onCreate() {
        this.createdAt = LocalDateTime.now();
        this.updatedAt = this.createdAt;
        if (this.role == null || this.role.isBlank()) {
            this.role = ROLE_OPS;
        }
        if (this.status == null || this.status.isBlank()) {
            this.status = STATUS_ACTIVE;
        }
        if (this.mustChangePassword == null) {
            this.mustChangePassword = true;
        }
        if (this.passwordChangeLocked == null) {
            this.passwordChangeLocked = false;
        }
        if (this.demoLoginEnabled == null) {
            this.demoLoginEnabled = false;
        }
        if (this.failedLoginCount == null) {
            this.failedLoginCount = 0;
        }
        if (this.aiQuotaUsed == null) {
            this.aiQuotaUsed = 0;
        }
    }

    @PreUpdate
    void onUpdate() {
        this.updatedAt = LocalDateTime.now();
    }
}
