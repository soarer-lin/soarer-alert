package com.soarer.alert.controller;

import com.soarer.alert.dto.ops.ApiResult;
import com.soarer.alert.dto.ops.PageResponse;
import com.soarer.alert.entity.AuthLoginAudit;
import com.soarer.alert.entity.AuthUser;
import com.soarer.alert.service.auth.AiQuotaService;
import com.soarer.alert.service.auth.AuthUserService;
import org.springframework.data.domain.Page;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 认证用户管理接口。
 */
@RestController
@RequestMapping("/api/admin")
public class AuthAdminController {

    private final AuthUserService userService;
    private final AiQuotaService aiQuotaService;

    public AuthAdminController(AuthUserService userService, AiQuotaService aiQuotaService) {
        this.userService = userService;
        this.aiQuotaService = aiQuotaService;
    }

    @GetMapping("/users")
    public ApiResult<PageResponse<UserResponse>> users(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size
    ) {
        Page<UserResponse> result = userService.listUsers(page, size).map(this::toResponse);
        return ApiResult.success(PageResponse.from(result));
    }

    @PostMapping("/users")
    public ResponseEntity<ApiResult<UserResponse>> create(@RequestBody CreateUserRequest request) {
        AuthUser user = userService.createUser(
                request.username(),
                request.displayName(),
                request.role(),
                request.password()
        );
        return ResponseEntity.ok(ApiResult.success(toResponse(user)));
    }

    @PutMapping("/users/{userId}")
    public ApiResult<UserResponse> update(
            @PathVariable UUID userId,
            @RequestBody UpdateUserRequest request
    ) {
        AuthUser user = userService.updateUser(userId, request.displayName(), request.role());
        return ApiResult.success(toResponse(user));
    }

    @PostMapping("/users/{userId}/reset-password")
    public ApiResult<UserResponse> resetPassword(
            @PathVariable UUID userId,
            @RequestBody ResetPasswordRequest request
    ) {
        AuthUser user = userService.resetPassword(userId, request.password());
        return ApiResult.success(toResponse(user));
    }

    @PostMapping("/users/{userId}/disable")
    public ApiResult<UserResponse> disable(@PathVariable UUID userId) {
        userService.ensureNotSelf(userId);
        AuthUser user = userService.setUserStatus(userId, AuthUser.STATUS_DISABLED);
        return ApiResult.success(toResponse(user));
    }

    @PostMapping("/users/{userId}/enable")
    public ApiResult<UserResponse> enable(@PathVariable UUID userId) {
        AuthUser user = userService.setUserStatus(userId, AuthUser.STATUS_ACTIVE);
        return ApiResult.success(toResponse(user));
    }

    @PostMapping("/users/{userId}/lock-password")
    public ApiResult<UserResponse> lockPassword(@PathVariable UUID userId) {
        AuthUser user = userService.setPasswordChangeLocked(userId, true);
        return ApiResult.success(toResponse(user));
    }

    @PostMapping("/users/{userId}/unlock-password")
    public ApiResult<UserResponse> unlockPassword(@PathVariable UUID userId) {
        AuthUser user = userService.setPasswordChangeLocked(userId, false);
        return ApiResult.success(toResponse(user));
    }

    @PostMapping("/users/{userId}/enable-demo-login")
    public ApiResult<UserResponse> enableDemoLogin(@PathVariable UUID userId) {
        AuthUser user = userService.setDemoLoginEnabled(userId, true);
        return ApiResult.success(toResponse(user));
    }

    @PostMapping("/users/{userId}/disable-demo-login")
    public ApiResult<UserResponse> disableDemoLogin(@PathVariable UUID userId) {
        AuthUser user = userService.setDemoLoginEnabled(userId, false);
        return ApiResult.success(toResponse(user));
    }

    @PostMapping("/users/{userId}/ai-quota")
    public ApiResult<UserResponse> setAiQuota(
            @PathVariable UUID userId,
            @RequestBody SetAiQuotaRequest request
    ) {
        AuthUser user = aiQuotaService.setQuota(userId, request.aiQuotaLimit());
        return ApiResult.success(toResponse(user));
    }

    @GetMapping("/login-audit")
    public ApiResult<PageResponse<LoginAuditResponse>> loginAudit(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size
    ) {
        Page<LoginAuditResponse> result = userService.listLoginAudit(page, size).map(this::toResponse);
        return ApiResult.success(PageResponse.from(result));
    }

    private UserResponse toResponse(AuthUser user) {
        return new UserResponse(
                user.getId(),
                user.getUsername(),
                user.getDisplayName(),
                user.getRole(),
                user.getStatus(),
                user.getMustChangePassword(),
                user.getPasswordChangeLocked(),
                Boolean.TRUE.equals(user.getDemoLoginEnabled()),
                user.getLastLoginAt(),
                user.getCreatedAt(),
                user.getAiQuotaLimit(),
                usedQuota(user),
                remainingQuota(user)
        );
    }

    private int usedQuota(AuthUser user) {
        return user.getAiQuotaUsed() == null ? 0 : user.getAiQuotaUsed();
    }

    private Integer remainingQuota(AuthUser user) {
        if (user.getAiQuotaLimit() == null) {
            return null;
        }
        return Math.max(user.getAiQuotaLimit() - usedQuota(user), 0);
    }

    private LoginAuditResponse toResponse(AuthLoginAudit audit) {
        return new LoginAuditResponse(
                audit.getId(),
                audit.getUsername(),
                audit.getSuccess(),
                audit.getIpAddress(),
                audit.getFailReason(),
                audit.getCreatedAt()
        );
    }

    public record CreateUserRequest(
            String username,
            String displayName,
            String role,
            String password
    ) {
    }

    public record UpdateUserRequest(String displayName, String role) {
    }

    public record ResetPasswordRequest(String password) {
    }

    public record SetAiQuotaRequest(Integer aiQuotaLimit) {
    }

    public record UserResponse(
            UUID id,
            String username,
            String displayName,
            String role,
            String status,
            boolean mustChangePassword,
            boolean passwordChangeLocked,
            boolean demoLoginEnabled,
            LocalDateTime lastLoginAt,
            LocalDateTime createdAt,
            Integer aiQuotaLimit,
            Integer aiQuotaUsed,
            Integer aiQuotaRemaining
    ) {
    }

    public record LoginAuditResponse(
            Long id,
            String username,
            boolean success,
            String ipAddress,
            String failReason,
            LocalDateTime createdAt
    ) {
    }
}
