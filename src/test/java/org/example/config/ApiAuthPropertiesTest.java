package org.example.config;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ApiAuthPropertiesTest {

    @Test
    void exposesLoginHardeningSettings() {
        ApiAuthProperties properties = new ApiAuthProperties();
        properties.setAdminUsername("admin");
        properties.setAdminInitialPassword("initial-password");
        properties.setMaxFailedAttempts(5);
        properties.setLockDurationMinutes(15);

        assertThat(properties.getAdminUsername()).isEqualTo("admin");
        assertThat(properties.getMaxFailedAttempts()).isEqualTo(5);
        assertThat(properties.getLockDurationMinutes()).isEqualTo(15);
    }
}
