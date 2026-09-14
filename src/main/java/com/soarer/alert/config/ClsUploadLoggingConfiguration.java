package com.soarer.alert.config;

import ch.qos.logback.classic.Logger;
import com.soarer.alert.logging.ClsLogbackAppender;
import com.soarer.alert.service.cls.ClsLogEventUploader;
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
