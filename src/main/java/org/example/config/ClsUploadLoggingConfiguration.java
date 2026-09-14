package org.example.config;

import ch.qos.logback.classic.Logger;
import org.example.logging.ClsLogbackAppender;
import org.example.service.cls.ClsLogEventUploader;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConditionalOnProperty(prefix = "cls.upload", name = "enabled", havingValue = "true")
public class ClsUploadLoggingConfiguration {

    @Bean(destroyMethod = "stop")
    public ClsLogbackAppender clsLogbackAppender(ClsLogEventUploader uploader) {
        ClsLogbackAppender appender = new ClsLogbackAppender(uploader);
        if (!appender.safeStart()) {
            return appender;
        }

        Logger rootLogger = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
        rootLogger.addAppender(appender);
        return appender;
    }
}
