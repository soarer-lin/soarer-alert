package com.soarer.alert.config;

import jakarta.annotation.PostConstruct;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.util.PlaceholderResolutionException;

/**
 * 生产环境关键配置校验。
 */
@Configuration
@Profile("prod")
public class ProductionConfigurationGuard {

    private final Environment environment;

    public ProductionConfigurationGuard(Environment environment) {
        this.environment = environment;
    }

    @PostConstruct
    public void validate() {
        requireFlag("spring.ai.dashscope.api-key", "DashScope API key");
        requireEnabled("spring.ai.mcp.client.enabled", "MCP client");
        rejectFlag("prometheus.mock-enabled", "Prometheus mock");
        rejectFlag("cls.mock-enabled", "CLS mock");
        requireEnabled("cls.upload.enabled", "CLS upload");
        requireAdminUsername("app.auth.admin-username");
        requireAdminPassword("app.auth.admin-initial-password");
        requireFlag("spring.datasource.password", "PostgreSQL password");
        rejectValue("spring.datasource.password", "superbiz_local_password");
        requireFlag("spring.data.redis.password", "Redis password");
        rejectValue("spring.data.redis.password", "superbiz_local_password");
        requireFlag("app.storage.access-key", "RustFS access key");
        rejectValue("app.storage.access-key", "rustfsadmin");
        requireFlag("app.storage.secret-key", "RustFS secret key");
        rejectValue("app.storage.secret-key", "rustfsadmin");
        requireFlag("cls.upload.secret-id", "CLS writer SecretId");
        requireFlag("cls.upload.secret-key", "CLS writer SecretKey");
        requireFlag("cls.upload.topics.app", "CLS app topic");
        requireFlag("cls.upload.topics.diagnosis", "CLS diagnosis topic");
    }

    private void requireFlag(String property, String name) {
        String value = propertyValue(property);
        if (isMissing(value)) {
            throw new IllegalStateException("Production configuration is missing " + name);
        }
    }

    private void requireEnabled(String property, String name) {
        String value = propertyValue(property);
        if (!Boolean.parseBoolean(value)) {
            throw new IllegalStateException(name + " must be enabled in production");
        }
    }

    private void requireAdminUsername(String property) {
        String value = propertyValue(property);
        if (isMissing(value) || value.strip().length() < 3) {
            throw new IllegalStateException("Production admin username must be configured");
        }
    }

    private void requireAdminPassword(String property) {
        String value = propertyValue(property);
        if (isMissing(value) || value.strip().length() < 8) {
            throw new IllegalStateException("Production admin initial password must be configured and strong enough");
        }
    }

    private boolean isMissing(String value) {
        return value == null || value.isBlank() || value.contains("${");
    }

    private String propertyValue(String property) {
        try {
            return environment.getProperty(property);
        } catch (PlaceholderResolutionException exception) {
            return null;
        }
    }

    private void rejectFlag(String property, String name) {
        if (Boolean.parseBoolean(environment.getProperty(property, "false"))) {
            throw new IllegalStateException(name + " must be disabled in production");
        }
    }

    private void rejectValue(String property, String rejectedValue) {
        if (rejectedValue.equals(environment.getProperty(property))) {
            throw new IllegalStateException("Production configuration must not use the default value for " + property);
        }
    }
}
