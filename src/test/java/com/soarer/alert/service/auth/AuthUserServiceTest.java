package com.soarer.alert.service.auth;

import com.soarer.alert.config.ApiAuthProperties;
import com.soarer.alert.entity.AuthUser;
import com.soarer.alert.repository.AuthLoginAuditRepository;
import com.soarer.alert.repository.AuthUserRepository;
import com.soarer.alert.security.AuthUserDetails;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.core.Authentication;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AuthUserServiceTest {

    private final AuthUserRepository userRepository = mock(AuthUserRepository.class);
    private final AuthLoginAuditRepository auditRepository = mock(AuthLoginAuditRepository.class);
    private final PasswordEncoder passwordEncoder = mock(PasswordEncoder.class);
    private AuthUserService service;

    @BeforeEach
    void setUp() {
        ApiAuthProperties properties = new ApiAuthProperties();
        properties.setMaxFailedAttempts(5);
        properties.setLockDurationMinutes(15);
        service = new AuthUserService(
                userRepository,
                auditRepository,
                passwordEncoder,
                properties
        );
    }

    @Test
    void adminCreatesAnOpsUserThatMustChangePassword() {
        when(userRepository.existsByUsername("ops-user")).thenReturn(false);
        when(passwordEncoder.encode("safe-password")).thenReturn("encoded-password");
        when(userRepository.save(any(AuthUser.class))).thenAnswer(invocation -> invocation.getArgument(0));

        AuthUser user = service.createUser(
                "ops-user",
                null,
                "OPS",
                "safe-password"
        );

        ArgumentCaptor<AuthUser> userCaptor = ArgumentCaptor.forClass(AuthUser.class);
        verify(userRepository).save(userCaptor.capture());
        assertThat(user.getUsername()).isEqualTo("ops-user");
        assertThat(user.getRole()).isEqualTo(AuthUser.ROLE_OPS);
        assertThat(user.getStatus()).isEqualTo(AuthUser.STATUS_ACTIVE);
        assertThat(user.getMustChangePassword()).isTrue();
    }

    @Test
    void createUserRejectsRemovedUserRole() {
        when(userRepository.existsByUsername("legacy-user")).thenReturn(false);

        assertThatThrownBy(() -> service.createUser("legacy-user", null, "USER", "safe-password"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("角色只能是 ADMIN 或 OPS");
    }

    @Test
    void changePasswordRejectsLockedTestAccount() {
        UUID userId = UUID.randomUUID();
        AuthUser user = user(userId, AuthUser.ROLE_OPS);
        user.setPasswordChangeLocked(true);
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));

        assertThatThrownBy(() -> service.changePassword(new AuthUserDetails(user), "old-password", "new-password"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("测试账号不允许改密码");
    }

    @Test
    void resettingLockedAccountDoesNotForcePasswordChange() {
        UUID userId = UUID.randomUUID();
        AuthUser user = user(userId, AuthUser.ROLE_OPS);
        user.setPasswordChangeLocked(true);
        user.setMustChangePassword(true);
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        when(passwordEncoder.encode("safe-password")).thenReturn("encoded-password");
        when(userRepository.save(any(AuthUser.class))).thenAnswer(invocation -> invocation.getArgument(0));

        AuthUser result = service.resetPassword(userId, "safe-password");

        assertThat(result.getPasswordHash()).isEqualTo("encoded-password");
        assertThat(result.getMustChangePassword()).isFalse();
    }

    @Test
    void lockingPasswordChangeClearsForcedPasswordChange() {
        UUID userId = UUID.randomUUID();
        AuthUser user = user(userId, AuthUser.ROLE_OPS);
        user.setMustChangePassword(true);
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        when(userRepository.save(any(AuthUser.class))).thenAnswer(invocation -> invocation.getArgument(0));

        AuthUser result = service.setPasswordChangeLocked(userId, true);

        assertThat(result.getPasswordChangeLocked()).isTrue();
        assertThat(result.getMustChangePassword()).isFalse();
    }

    @Test
    void issueDemoLoginCredentialsCreatesARotatingPassword() {
        AuthUser user = user(UUID.randomUUID(), AuthUser.ROLE_OPS);
        user.setDemoLoginEnabled(true);
        when(userRepository.findFirstByDemoLoginEnabledTrueAndStatusOrderByCreatedAtDesc(AuthUser.STATUS_ACTIVE))
                .thenReturn(Optional.of(user));
        when(passwordEncoder.encode(any(CharSequence.class))).thenReturn("encoded-demo-password");
        when(userRepository.save(any(AuthUser.class))).thenAnswer(invocation -> invocation.getArgument(0));

        AuthUserService.DemoLoginCredentials credentials = service.issueDemoLoginCredentials();

        assertThat(credentials.username()).isEqualTo("ops-user");
        assertThat(credentials.password()).hasSize(18);
        assertThat(user.getDemoPasswordHash()).isEqualTo("encoded-demo-password");
    }

    @Test
    void loginAcceptsDemoPasswordWithoutExposingRealPassword() {
        AuthUser user = user(UUID.randomUUID(), AuthUser.ROLE_OPS);
        user.setDemoLoginEnabled(true);
        user.setDemoPasswordHash("encoded-demo-password");
        when(userRepository.findByUsername("ops-user")).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("wrong-real-password", "encoded-password")).thenReturn(false);
        when(passwordEncoder.matches("demo-password", "encoded-password")).thenReturn(false);
        when(passwordEncoder.matches("demo-password", "encoded-demo-password")).thenReturn(true);
        when(userRepository.save(any(AuthUser.class))).thenAnswer(invocation -> invocation.getArgument(0));

        Authentication authentication = service.login("ops-user", "demo-password", "127.0.0.1", "test-agent");

        assertThat(authentication.isAuthenticated()).isTrue();
        assertThat(authentication.getName()).isEqualTo("ops-user");
    }

    @Test
    void enablingDemoLoginReplacesPreviousDemoAccount() {
        UUID userId = UUID.randomUUID();
        AuthUser user = user(userId, AuthUser.ROLE_OPS);
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        when(userRepository.save(any(AuthUser.class))).thenAnswer(invocation -> invocation.getArgument(0));

        AuthUser result = service.setDemoLoginEnabled(userId, true);

        verify(userRepository).clearDemoLogin();
        assertThat(result.getDemoLoginEnabled()).isTrue();
        assertThat(result.getDemoPasswordHash()).isNull();
    }

    private AuthUser user(UUID id, String role) {
        AuthUser user = new AuthUser();
        user.setId(id);
        user.setUsername("ops-user");
        user.setPasswordHash("encoded-password");
        user.setRole(role);
        user.setStatus(AuthUser.STATUS_ACTIVE);
        user.setMustChangePassword(false);
        user.setFailedLoginCount(0);
        user.setPasswordChangeLocked(false);
        return user;
    }
}
