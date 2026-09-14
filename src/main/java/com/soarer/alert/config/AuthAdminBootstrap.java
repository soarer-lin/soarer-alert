package com.soarer.alert.config;

import jakarta.annotation.PostConstruct;
import com.soarer.alert.entity.AuthUser;
import com.soarer.alert.repository.AuthUserRepository;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

@Component
public class AuthAdminBootstrap {

    private final ApiAuthProperties properties;
    private final AuthUserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    public AuthAdminBootstrap(
            ApiAuthProperties properties,
            AuthUserRepository userRepository,
            PasswordEncoder passwordEncoder
    ) {
        this.properties = properties;
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
    }

    @PostConstruct
    public void bootstrap() {
        if (userRepository.countByRoleAndStatus(AuthUser.ROLE_ADMIN, AuthUser.STATUS_ACTIVE) > 0) {
            return;
        }

        if (isBlank(properties.getAdminUsername()) || isBlank(properties.getAdminInitialPassword())) {
            throw new IllegalStateException(
                    "No active administrator exists. Set AUTH_ADMIN_USERNAME and AUTH_ADMIN_INITIAL_PASSWORD before startup."
            );
        }

        String username = properties.getAdminUsername().trim();
        String initialPassword = properties.getAdminInitialPassword();
        validateUsername(username);
        validatePassword(initialPassword, username);
        if (userRepository.existsByUsername(username)) {
            throw new IllegalStateException(
                    "Configured admin username already exists but is not an active administrator."
            );
        }

        AuthUser admin = new AuthUser();
        admin.setUsername(username);
        admin.setPasswordHash(passwordEncoder.encode(initialPassword));
        admin.setDisplayName("系统管理员");
        admin.setRole(AuthUser.ROLE_ADMIN);
        admin.setStatus(AuthUser.STATUS_ACTIVE);
        admin.setMustChangePassword(true);
        admin.setPasswordUpdatedAt(LocalDateTime.now());
        userRepository.save(admin);
    }

    private void validateUsername(String username) {
        if (!username.matches("^[A-Za-z0-9_.-]{3,64}$")) {
            throw new IllegalStateException("AUTH_ADMIN_USERNAME must contain 3-64 letters, digits, dots, underscores or hyphens.");
        }
    }

    private void validatePassword(String password, String username) {
        if (password.length() < 8) {
            throw new IllegalStateException("AUTH_ADMIN_INITIAL_PASSWORD must contain at least 8 characters.");
        }
        if (password.contains(username)) {
            throw new IllegalStateException("AUTH_ADMIN_INITIAL_PASSWORD must not contain the admin username.");
        }
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
