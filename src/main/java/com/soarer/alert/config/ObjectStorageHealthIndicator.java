package com.soarer.alert.config;

import com.soarer.alert.service.storage.ObjectStorageException;
import com.soarer.alert.service.storage.ObjectStorageService;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;

/**
 * ObjectStorageHealthIndicator Spring 配置。
 */
@Component
public class ObjectStorageHealthIndicator implements HealthIndicator {

    private final ObjectStorageService objectStorageService;

    public ObjectStorageHealthIndicator(ObjectStorageService objectStorageService) {
        this.objectStorageService = objectStorageService;
    }

    @Override
    public Health health() {
        try {
            objectStorageService.ensureBucketExists();
            return Health.up()
                    .withDetail("bucket", objectStorageService.getBucketName())
                    .withDetail("storage", "rustfs")
                    .build();
        } catch (ObjectStorageException e) {
            return Health.down(e)
                    .withDetail("bucket", objectStorageService.getBucketName())
                    .withDetail("storage", "rustfs")
                    .build();
        }
    }
}
