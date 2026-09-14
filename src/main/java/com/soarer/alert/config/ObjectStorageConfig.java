package com.soarer.alert.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;

import java.net.URI;

@Configuration
public class ObjectStorageConfig {

    private final ObjectStorageConfigProperties properties;

    public ObjectStorageConfig(ObjectStorageConfigProperties properties) {
        this.properties = properties;
    }

    @Bean
    public S3Client s3Client() {
        validate();
        AwsBasicCredentials credentials = AwsBasicCredentials.create(
                properties.getAccessKey(),
                properties.getSecretKey()
        );

        ClientOverrideConfiguration.Builder override = ClientOverrideConfiguration.builder();
        if (properties.getApiCallTimeout() != null) {
            override.apiCallTimeout(properties.getApiCallTimeout());
        }
        if (properties.getApiCallAttemptTimeout() != null) {
            override.apiCallAttemptTimeout(properties.getApiCallAttemptTimeout());
        }

        return S3Client.builder()
                .endpointOverride(URI.create(properties.getEndpoint()))
                .region(Region.of(properties.getRegion()))
                .credentialsProvider(StaticCredentialsProvider.create(credentials))
                .overrideConfiguration(override.build())
                .forcePathStyle(true)
                .build();
    }

    private void validate() {
        if (isBlank(properties.getEndpoint())) {
            throw new IllegalStateException("app.storage.endpoint is required");
        }
        if (isBlank(properties.getAccessKey()) || isBlank(properties.getSecretKey())) {
            throw new IllegalStateException("app.storage.access-key and app.storage.secret-key are required");
        }
        if (isBlank(properties.getBucket())) {
            throw new IllegalStateException("app.storage.bucket is required");
        }
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
