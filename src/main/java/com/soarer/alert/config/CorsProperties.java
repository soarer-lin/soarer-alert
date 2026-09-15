package com.soarer.alert.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.util.ArrayList;
import java.util.List;

/**
 * CorsProperties Spring 配置。
 */
@Configuration
@ConfigurationProperties(prefix = "app.cors")
public class CorsProperties {

    private List<String> allowedOrigins = new ArrayList<>(List.of("*"));

    public List<String> getAllowedOrigins() {
        return allowedOrigins;
    }

    public void setAllowedOrigins(List<String> allowedOrigins) {
        this.allowedOrigins = allowedOrigins;
    }
}
