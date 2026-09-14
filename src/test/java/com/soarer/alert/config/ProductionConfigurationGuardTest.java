package com.soarer.alert.config;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProductionConfigurationGuardTest {

    @Test
    void rejectsUnresolvedPlaceholder() {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("spring.ai.dashscope.api-key", "${DASHSCOPE_API_KEY}");

        ProductionConfigurationGuard guard = new ProductionConfigurationGuard(environment);

        assertThatThrownBy(guard::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("DashScope API key");
    }

    @Test
    void rejectsMissingAdminBootstrap() {
        MockEnvironment environment = validEnvironment()
                .withProperty("app.auth.admin-initial-password", "");

        ProductionConfigurationGuard guard = new ProductionConfigurationGuard(environment);

        assertThatThrownBy(guard::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("admin initial password");
    }

    @Test
    void acceptsValidProductionConfiguration() {
        ProductionConfigurationGuard guard = new ProductionConfigurationGuard(validEnvironment());

        guard.validate();
    }

    @Test
    void rejectsDisabledRequiredIntegration() {
        MockEnvironment environment = validEnvironment()
                .withProperty("cls.upload.enabled", "false");

        ProductionConfigurationGuard guard = new ProductionConfigurationGuard(environment);

        assertThatThrownBy(guard::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("CLS upload must be enabled");
    }

    @Test
    void rejectsMissingRedisPassword() {
        MockEnvironment environment = validEnvironment()
                .withProperty("spring.data.redis.password", "");

        ProductionConfigurationGuard guard = new ProductionConfigurationGuard(environment);

        assertThatThrownBy(guard::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Redis password");
    }

    private static MockEnvironment validEnvironment() {
        return new MockEnvironment()
                .withProperty("spring.ai.dashscope.api-key", "real-dashscope-key")
                .withProperty("spring.ai.mcp.client.enabled", "true")
                .withProperty("prometheus.mock-enabled", "false")
                .withProperty("cls.mock-enabled", "false")
                .withProperty("cls.upload.enabled", "true")
                .withProperty("app.auth.admin-username", "admin")
                .withProperty("app.auth.admin-initial-password", "initial-admin-password")
                .withProperty("spring.datasource.password", "production-postgres-password")
                .withProperty("spring.data.redis.password", "production-redis-password")
                .withProperty("app.storage.access-key", "production-access-key")
                .withProperty("app.storage.secret-key", "production-secret-key")
                .withProperty("cls.upload.secret-id", "production-cls-secret-id")
                .withProperty("cls.upload.secret-key", "production-cls-secret-key")
                .withProperty("cls.upload.topics.app", "production-app-topic")
                .withProperty("cls.upload.topics.diagnosis", "production-diagnosis-topic");
    }
}
