package org.example.config;

import org.example.entity.AuthUser;
import org.example.repository.AuthUserRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.crypto.password.PasswordEncoder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AuthAdminBootstrapTest {

    private final AuthUserRepository userRepository = mock(AuthUserRepository.class);
    private final PasswordEncoder passwordEncoder = mock(PasswordEncoder.class);
    private final AuthAdminBootstrap bootstrap =
            new AuthAdminBootstrap(new ApiAuthProperties(), userRepository, passwordEncoder);

    @Test
    void requiresBootstrapVariablesWhenNoActiveAdminExists() {
        when(userRepository.countByRoleAndStatus(AuthUser.ROLE_ADMIN, AuthUser.STATUS_ACTIVE))
                .thenReturn(0L);

        assertThatThrownBy(bootstrap::bootstrap)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("AUTH_ADMIN_USERNAME");
        verify(userRepository, never()).save(any());
    }

    @Test
    void doesNotRequireBootstrapVariablesWhenActiveAdminExists() {
        when(userRepository.countByRoleAndStatus(AuthUser.ROLE_ADMIN, AuthUser.STATUS_ACTIVE))
                .thenReturn(1L);

        bootstrap.bootstrap();

        verify(userRepository, never()).save(any());
    }

    @Test
    void createsFirstAdministratorWithPasswordChangeRequired() {
        ApiAuthProperties properties = new ApiAuthProperties();
        properties.setAdminUsername("admin");
        properties.setAdminInitialPassword("initial-password");
        AuthAdminBootstrap bootstrap = new AuthAdminBootstrap(properties, userRepository, passwordEncoder);

        when(userRepository.countByRoleAndStatus(AuthUser.ROLE_ADMIN, AuthUser.STATUS_ACTIVE))
                .thenReturn(0L);
        when(userRepository.existsByUsername("admin")).thenReturn(false);
        when(passwordEncoder.encode("initial-password")).thenReturn("encoded-password");

        bootstrap.bootstrap();

        ArgumentCaptor<AuthUser> captor = ArgumentCaptor.forClass(AuthUser.class);
        verify(userRepository).save(captor.capture());
        AuthUser admin = captor.getValue();
        assertThat(admin.getUsername()).isEqualTo("admin");
        assertThat(admin.getPasswordHash()).isEqualTo("encoded-password");
        assertThat(admin.getRole()).isEqualTo(AuthUser.ROLE_ADMIN);
        assertThat(admin.getStatus()).isEqualTo(AuthUser.STATUS_ACTIVE);
        assertThat(admin.getMustChangePassword()).isTrue();
    }

    @Test
    void rejectsWeakInitialPassword() {
        ApiAuthProperties properties = new ApiAuthProperties();
        properties.setAdminUsername("admin");
        properties.setAdminInitialPassword("short");
        AuthAdminBootstrap bootstrap = new AuthAdminBootstrap(properties, userRepository, passwordEncoder);
        when(userRepository.countByRoleAndStatus(AuthUser.ROLE_ADMIN, AuthUser.STATUS_ACTIVE))
                .thenReturn(0L);

        assertThatThrownBy(bootstrap::bootstrap)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("at least 8 characters");
        verify(userRepository, never()).save(any());
    }
}
