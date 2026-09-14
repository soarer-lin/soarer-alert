package com.soarer.alert.service.storage;

import com.soarer.alert.config.ObjectStorageConfigProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;
import software.amazon.awssdk.services.s3.model.HeadBucketRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ObjectStorageServiceTest {

    private S3Client s3Client;
    private ObjectStorageConfigProperties properties;
    private ObjectStorageService storageService;

    @BeforeEach
    void setUp() {
        s3Client = mock(S3Client.class);
        properties = new ObjectStorageConfigProperties();
        properties.setEndpoint("http://localhost:19000");
        properties.setAccessKey("test-access-key");
        properties.setSecretKey("test-secret-key");
        properties.setBucket("superbiz-agent");
        properties.setRegion("us-east-1");
        properties.setAutoCreateBucket(false);
        storageService = new ObjectStorageService(s3Client, properties);
    }

    @Test
    void uploadDocumentUsesSafeKeyAndMetadata() {
        String key = storageService.uploadDocument(
                "runbook".getBytes(),
                "Ops Runbook.md",
                "text/markdown"
        );

        assertThat(key).startsWith("documents/");
        assertThat(key).contains(LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy/MM/dd")) + "/");
        assertThat(key).endsWith("_ops_runbook.md");
        assertThat(key).doesNotContain(" ");

        ArgumentCaptor<PutObjectRequest> requestCaptor = ArgumentCaptor.forClass(PutObjectRequest.class);
        verify(s3Client).putObject(requestCaptor.capture(), any(RequestBody.class));
        PutObjectRequest request = requestCaptor.getValue();
        assertThat(request.bucket()).isEqualTo("superbiz-agent");
        assertThat(request.key()).isEqualTo(key);
        assertThat(request.contentType()).isEqualTo("text/markdown");
        assertThat(request.contentLength()).isEqualTo(7L);
        assertThat(storageService.getObjectUrl(key))
                .isEqualTo("http://localhost:19000/superbiz-agent/" + key);
    }

    @Test
    void objectExistsReturnsFalseWhenKeyIsMissing() {
        when(s3Client.headObject(any(HeadObjectRequest.class)))
                .thenThrow(NoSuchKeyException.builder().message("missing").build());

        assertThat(storageService.objectExists("documents/missing.md")).isFalse();
    }

    @Test
    void ensureBucketExistsCreatesMissingBucket() {
        when(s3Client.headBucket(any(HeadBucketRequest.class)))
                .thenThrow((S3Exception) S3Exception.builder().statusCode(404).build());

        storageService.ensureBucketExists();

        ArgumentCaptor<CreateBucketRequest> requestCaptor = ArgumentCaptor.forClass(CreateBucketRequest.class);
        verify(s3Client).createBucket(requestCaptor.capture());
        assertThat(requestCaptor.getValue().bucket()).isEqualTo("superbiz-agent");
    }
}
