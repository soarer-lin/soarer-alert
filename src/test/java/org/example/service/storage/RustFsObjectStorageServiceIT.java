package org.example.service.storage;

import org.example.config.ObjectStorageConfig;
import org.example.config.ObjectStorageConfigProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import software.amazon.awssdk.services.s3.S3Client;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@EnabledIfSystemProperty(named = "rustfs.integration", matches = "true")
class RustFsObjectStorageServiceIT {

    @Test
    void uploadsDownloadsAndDeletesObject() {
        ObjectStorageConfigProperties properties = new ObjectStorageConfigProperties();
        properties.setEndpoint("http://localhost:19000");
        properties.setAccessKey("rustfsadmin");
        properties.setSecretKey("rustfsadmin");
        properties.setBucket("superbiz-agent");
        properties.setRegion("us-east-1");
        properties.setAutoCreateBucket(true);

        try (S3Client s3Client = new ObjectStorageConfig(properties).s3Client()) {
            ObjectStorageService service = new ObjectStorageService(s3Client, properties);
            service.ensureBucketExists();

            String fileName = "integration-" + UUID.randomUUID() + ".txt";
            String key = service.uploadDocument(
                    "rustfs integration".getBytes(StandardCharsets.UTF_8),
                    fileName,
                    "text/plain"
            );

            try {
                assertThat(service.objectExists(key)).isTrue();
                assertThat(service.downloadObject(key))
                        .isEqualTo("rustfs integration".getBytes(StandardCharsets.UTF_8));
            } finally {
                service.deleteObject(key);
            }

            assertThat(service.objectExists(key)).isFalse();
        }
    }
}
