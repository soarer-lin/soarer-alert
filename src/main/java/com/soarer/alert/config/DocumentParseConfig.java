package com.soarer.alert.config;

import lombok.Getter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Getter
@Configuration
@ConfigurationProperties(prefix = "document.parse")
public class DocumentParseConfig {

    private int maxTextChars = 2_000_000;

    public void setMaxTextChars(int maxTextChars) {
        this.maxTextChars = maxTextChars;
    }
}
