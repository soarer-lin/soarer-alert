package org.example.service.auth;

import org.example.config.ApiAuthProperties;
import org.example.entity.AuthLoginAudit;
import org.example.entity.AuthUser;
import org.example.repository.AuthLoginAuditRepository;
import org.example.repository.AuthUserRepository;
import org.example.security.AuthUserDetails;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.authentication.LockedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.security.SecureRandom;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

@Service
public class AuthUserService {

    private static final Pattern USERNAME_PATTERN = Pattern.compile("^[A-Za-z0-9_.-]{3,64}$");
    private static final int MAX_PAGE_SIZE = 100;
    private static final String DEMO_PASSWORD_CHARACTERS =
            "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789";
    private static final int DEMO_PASSWORD_LENGTH = 18;
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private final AuthUserRepository userRepository;
    private final AuthLoginAuditRepository auditRepository;
    private final PasswordEncoder passwordEncoder;
    private final ApiAuthProperties authProperties;

    public AuthUserService(
            AuthUserRepository userRepository,
            AuthLoginAuditRepository auditRepository,
            PasswordEncoder passwordEncoder,
            ApiAuthProperties authProperties
    ) {
        this.userRepository = userRepository;
        this.auditRepository = auditRepository;
        this.passwordEncoder = passwordEncoder;
        this.authProperties = authProperties;
    }

    public Authentication login(String username, String password, String ipAddress, String userAgent) {
        String attemptedAccount = username == null ? "" : username.trim();
        Optional<AuthUser> found = userRepository.findByUsername(attemptedAccount);

        if (found.isEmpty()) {
            audit(null, attemptedAccount, false, "USER_NOT_FOUND", ipAddress, userAgent);
            throw new BadCredentialsException("用户名或密码错误");
        }

        AuthUser user = found.get();
        if (AuthUser.STATUS_DISABLED.equals(user.getStatus())) {
            audit(user, user.getUsername(), false, "ACCOUNT_DISABLED", ipAddress, userAgent);
            throw new DisabledException("账号已禁用");
        }
        if (user.getLockedUntil() != null && user.getLockedUntil().isAfter(LocalDateTime.now())) {
            audit(user, user.getUsername(), false, "ACCOUNT_LOCKED", ipAddress, userAgent);
            throw new LockedException("账号已锁定，请稍后再试");
        }
        String attemptedPassword = password == null ? "" : password;
        boolean validPassword = passwordEncoder.matches(attemptedPassword, user.getPasswordHash());
        if (!validPassword
                && Boolean.TRUE.equals(user.getDemoLoginEnabled())
                && user.getDemoPasswordHash() != null) {
            validPassword = passwordEncoder.matches(attemptedPassword, user.getDemoPasswordHash());
        }
        if (!validPassword) {
            registerFailedLogin(user, ipAddress, userAgent);
            throw new BadCredentialsException("用户名或密码错误");
        }

        user.setFailedLoginCount(0);
        user.setLockedUntil(null);
        user.setLastLoginAt(LocalDateTime.now());
        userRepository.save(user);
        audit(user, user.getUsername(), true, null, ipAddress, userAgent);

        AuthUserDetails userDetails = new AuthUserDetails(user);
        return org.springframework.security.authentication.UsernamePasswordAuthenticationToken.authenticated(
                userDetails,
                null,
                userDetails.getAuthorities()
        );
    }

    public Optional<AuthUser> currentUser() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.getPrincipal() instanceof AuthUserDetails userDetails) {
            return Optional.of(userDetails.user());
        }
        return Optional.empty();
    }

    @Transactional
    public void changePassword(AuthUserDetails userDetails, String oldPassword, String newPassword) {
        AuthUser user = requireUser(userDetails.user().getId());
        if (Boolean.TRUE.equals(user.getPasswordChangeLocked())) {
            throw new IllegalArgumentException("测试账号不允许改密码");
        }
        if (!passwordEncoder.matches(oldPassword == null ? "" : oldPassword, user.getPasswordHash())) {
            throw new BadCredentialsException("原密码不正确");
        }
        validatePassword(newPassword, user.getUsername());
        user.setPasswordHash(passwordEncoder.encode(newPassword));
        user.setPasswordUpdatedAt(LocalDateTime.now());
        user.setMustChangePassword(false);
        user.setFailedLoginCount(0);
        user.setLockedUntil(null);
        userRepository.save(user);
    }

    public Page<AuthUser> listUsers(int page, int size) {
        return userRepository.findAllByOrderByCreatedAtDesc(pageable(page, size));
    }

    @Transactional
    public DemoLoginCredentials issueDemoLoginCredentials() {
        AuthUser user = userRepository
                .findFirstByDemoLoginEnabledTrueAndStatusOrderByCreatedAtDesc(AuthUser.STATUS_ACTIVE)
                .orElse(null);
        if (user == null) {
            return null;
        }

        String password = generateDemoPassword();
        user.setDemoPasswordHash(passwordEncoder.encode(password));
        userRepository.save(user);
        return new DemoLoginCredentials(user.getUsername(), password);
    }

    @Transactional
    public AuthUser createUser(String username, String displayName, String role, String password) {
        String normalizedUsername = normalizeUsername(username);
        String normalizedRole = normalizeRole(role);
        validatePassword(password, normalizedUsername);
        if (userRepository.existsByUsername(normalizedUsername)) {
            throw new IllegalArgumentException("用户名已存在");
        }

        AuthUser user = new AuthUser();
        user.setUsername(normalizedUsername);
        user.setDisplayName(normalizeDisplayName(displayName, normalizedUsername));
        user.setRole(normalizedRole);
        user.setStatus(AuthUser.STATUS_ACTIVE);
        user.setPasswordHash(passwordEncoder.encode(password));
        user.setMustChangePassword(true);
        user.setPasswordUpdatedAt(LocalDateTime.now());
        return userRepository.save(user);
    }

    @Transactional
    public AuthUser updateUser(UUID userId, String displayName, String role) {
        AuthUser user = requireUser(userId);
        String normalizedRole = normalizeRole(role);
        if (AuthUser.ROLE_ADMIN.equals(user.getRole()) && !AuthUser.ROLE_ADMIN.equals(normalizedRole)) {
            ensureNotLastActiveAdmin();
        }
        user.setDisplayName(normalizeDisplayName(displayName, user.getUsername()));
        user.setRole(normalizedRole);
        return userRepository.save(user);
    }

    @Transactional
    public AuthUser resetPassword(UUID userId, String password) {
        AuthUser user = requireUser(userId);
        validatePassword(password, user.getUsername());
        user.setPasswordHash(passwordEncoder.encode(password));
        user.setPasswordUpdatedAt(LocalDateTime.now());
        user.setMustChangePassword(!Boolean.TRUE.equals(user.getPasswordChangeLocked()));
        user.setFailedLoginCount(0);
        user.setLockedUntil(null);
        return userRepository.save(user);
    }

    @Transactional
    public AuthUser setUserStatus(UUID userId, String status) {
        AuthUser user = requireUser(userId);
        String normalizedStatus = normalizeStatus(status);
        if (AuthUser.STATUS_DISABLED.equals(normalizedStatus)
                && AuthUser.ROLE_ADMIN.equals(user.getRole())) {
            ensureNotLastActiveAdmin();
        }
        user.setStatus(normalizedStatus);
        user.setFailedLoginCount(0);
        user.setLockedUntil(null);
        if (AuthUser.STATUS_DISABLED.equals(normalizedStatus)) {
            user.setDemoLoginEnabled(false);
            user.setDemoPasswordHash(null);
        }
        return userRepository.save(user);
    }

    @Transactional
    public AuthUser setDemoLoginEnabled(UUID userId, boolean enabled) {
        AuthUser user = requireUser(userId);
        if (enabled) {
            if (!AuthUser.STATUS_ACTIVE.equals(user.getStatus())) {
                throw new IllegalArgumentException("只有启用状态的账号可以设为测试账号");
            }
            userRepository.clearDemoLogin();
            user.setDemoLoginEnabled(true);
        } else {
            user.setDemoLoginEnabled(false);
        }
        user.setDemoPasswordHash(null);
        return userRepository.save(user);
    }

    @Transactional
    public AuthUser setPasswordChangeLocked(UUID userId, boolean locked) {
        AuthUser user = requireUser(userId);
        user.setPasswordChangeLocked(locked);
        if (locked) {
            user.setMustChangePassword(false);
        }
        return userRepository.save(user);
    }

    public Page<AuthLoginAudit> listLoginAudit(int page, int size) {
        return auditRepository.findAllByOrderByCreatedAtDesc(pageable(page, size));
    }

    public AuthUser requireUser(UUID userId) {
        return userRepository.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("用户不存在"));
    }

    public void ensureNotSelf(UUID targetUserId) {
        currentUserId().filter(current -> current.equals(targetUserId))
                .ifPresent(current -> {
                    throw new IllegalArgumentException("不能对当前登录账号执行该操作");
                });
    }

    public Optional<UUID> currentUserId() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.getPrincipal() instanceof AuthUserDetails userDetails) {
            return Optional.of(userDetails.user().getId());
        }
        return Optional.empty();
    }

    private void registerFailedLogin(AuthUser user, String ipAddress, String userAgent) {
        int failedCount = (user.getFailedLoginCount() == null ? 0 : user.getFailedLoginCount()) + 1;
        user.setFailedLoginCount(failedCount);
        if (failedCount >= authProperties.getMaxFailedAttempts()) {
            user.setLockedUntil(LocalDateTime.now().plusMinutes(authProperties.getLockDurationMinutes()));
            user.setFailedLoginCount(0);
        }
        userRepository.save(user);
        audit(user, user.getUsername(), false,
                user.getLockedUntil() == null ? "BAD_CREDENTIALS" : "ACCOUNT_LOCKED",
                ipAddress, userAgent);
    }

    private void audit(
            AuthUser user,
            String attemptedUsername,
            boolean success,
            String failReason,
            String ipAddress,
            String userAgent
    ) {
        AuthLoginAudit audit = new AuthLoginAudit();
        audit.setUserId(user == null ? null : user.getId());
        audit.setUsername(attemptedUsername == null && user != null ? user.getUsername() : attemptedUsername);
        audit.setSuccess(success);
        audit.setFailReason(failReason);
        audit.setIpAddress(truncate(ipAddress, 64));
        audit.setUserAgent(truncate(userAgent, 500));
        auditRepository.save(audit);
    }

    private void ensureNotLastActiveAdmin() {
        if (userRepository.countByRoleAndStatus(AuthUser.ROLE_ADMIN, AuthUser.STATUS_ACTIVE) <= 1) {
            throw new IllegalArgumentException("系统必须保留至少一个可用的管理员账号");
        }
    }

    private String normalizeUsername(String username) {
        if (username == null || !USERNAME_PATTERN.matcher(username.trim()).matches()) {
            throw new IllegalArgumentException("用户名只能包含字母、数字、点、下划线和横线，长度为3到64位");
        }
        return username.trim();
    }

    private String normalizeRole(String role) {
        String normalizedRole = role == null ? "" : role.trim().toUpperCase();
        if (!AuthUser.ROLE_ADMIN.equals(normalizedRole)
                && !AuthUser.ROLE_OPS.equals(normalizedRole)) {
            throw new IllegalArgumentException("角色只能是 ADMIN 或 OPS");
        }
        return normalizedRole;
    }

    private String normalizeStatus(String status) {
        String normalizedStatus = status == null ? "" : status.trim().toUpperCase();
        if (!AuthUser.STATUS_ACTIVE.equals(normalizedStatus) && !AuthUser.STATUS_DISABLED.equals(normalizedStatus)) {
            throw new IllegalArgumentException("状态只能是 ACTIVE 或 DISABLED");
        }
        return normalizedStatus;
    }

    private String normalizeDisplayName(String displayName, String fallback) {
        if (displayName == null || displayName.isBlank()) {
            return fallback;
        }
        return truncate(displayName.trim(), 100);
    }

    private void validatePassword(String password, String username) {
        if (password == null || password.length() < 6) {
            throw new IllegalArgumentException("密码至少需要6位");
        }
        if (password.contains(username)) {
            throw new IllegalArgumentException("密码不能包含用户名");
        }
    }

    private Pageable pageable(int page, int size) {
        int safePage = Math.max(page, 0);
        int safeSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
        return PageRequest.of(safePage, safeSize, Sort.unsorted());
    }

    private String truncate(String value, int maxLength) {
        if (value == null) {
            return null;
        }
        return value.length() <= maxLength ? value : value.substring(0, maxLength);
    }

    private String generateDemoPassword() {
        StringBuilder password = new StringBuilder(DEMO_PASSWORD_LENGTH);
        for (int i = 0; i < DEMO_PASSWORD_LENGTH; i++) {
            password.append(DEMO_PASSWORD_CHARACTERS.charAt(SECURE_RANDOM.nextInt(DEMO_PASSWORD_CHARACTERS.length())));
        }
        return password.toString();
    }

    public record DemoLoginCredentials(String username, String password) {
    }
}
