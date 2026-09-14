package org.example.config;

import org.example.service.storage.ObjectStorageException;
import org.example.service.storage.ObjectStorageService;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;

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
