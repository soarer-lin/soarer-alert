package com.soarer.alert.service.storage;

import jakarta.annotation.PostConstruct;
import com.soarer.alert.config.ObjectStorageConfigProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadBucketRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.UUID;

@Service
public class ObjectStorageService {

    private static final Logger logger = LoggerFactory.getLogger(ObjectStorageService.class);
    private static final DateTimeFormatter DATE_PATH = DateTimeFormatter.ofPattern("yyyy/MM/dd");

    private final S3Client s3Client;
    private final ObjectStorageConfigProperties properties;

    public ObjectStorageService(S3Client s3Client, ObjectStorageConfigProperties properties) {
        this.s3Client = s3Client;
        this.properties = properties;
    }

    @PostConstruct
    public void initialize() {
        if (!properties.isAutoCreateBucket()) {
            logger.info("RustFS bucket startup check is disabled: {}", properties.getBucket());
            return;
        }
        ensureBucketExists();
    }

    public String uploadDocument(byte[] bytes, String fileName, String contentType) {
        if (bytes == null || bytes.length == 0) {
            throw new ObjectStorageException("上传到对象存储的文件不能为空");
        }
        String key = generateDocumentKey(fileName);
        PutObjectRequest request = PutObjectRequest.builder()
                .bucket(properties.getBucket())
                .key(key)
                .contentType(contentType)
                .contentLength((long) bytes.length)
                .build();
        try {
            s3Client.putObject(request, RequestBody.fromBytes(bytes));
            logger.info("Uploaded document to RustFS: {} ({} bytes)", key, bytes.length);
            return key;
        } catch (S3Exception e) {
            throw new ObjectStorageException("上传文件到 RustFS 失败: " + e.getMessage(), e);
        }
    }

    public boolean objectExists(String key) {
        requireKey(key);
        try {
            s3Client.headObject(HeadObjectRequest.builder()
                    .bucket(properties.getBucket())
                    .key(key)
                    .build());
            return true;
        } catch (NoSuchKeyException e) {
            return false;
        } catch (S3Exception e) {
            throw new ObjectStorageException("检查对象存储文件失败: " + e.getMessage(), e);
        }
    }

    public byte[] downloadObject(String key) {
        requireKey(key);
        try {
            return s3Client.getObjectAsBytes(GetObjectRequest.builder()
                    .bucket(properties.getBucket())
                    .key(key)
                    .build()).asByteArray();
        } catch (NoSuchKeyException e) {
            throw new ObjectStorageException("对象存储文件不存在: " + key);
        } catch (S3Exception e) {
            throw new ObjectStorageException("下载对象存储文件失败: " + e.getMessage(), e);
        }
    }

    public void deleteObject(String key) {
        requireKey(key);
        try {
            s3Client.deleteObject(DeleteObjectRequest.builder()
                    .bucket(properties.getBucket())
                    .key(key)
                    .build());
            logger.info("Deleted object from RustFS: {}", key);
        } catch (S3Exception e) {
            throw new ObjectStorageException("删除对象存储文件失败: " + e.getMessage(), e);
        }
    }

    public void ensureBucketExists() {
        try {
            s3Client.headBucket(HeadBucketRequest.builder()
                    .bucket(properties.getBucket())
                    .build());
            logger.info("RustFS bucket is ready: {}", properties.getBucket());
        } catch (S3Exception e) {
            if (e.statusCode() != 404) {
                throw new ObjectStorageException("检查 RustFS bucket 失败: " + e.getMessage(), e);
            }
            createBucket();
        }
    }

    public String getBucketName() {
        return properties.getBucket();
    }

    public String getObjectUrl(String key) {
        requireKey(key);
        return properties.getEndpoint() + "/" + properties.getBucket() + "/" + key;
    }

    private void createBucket() {
        try {
            s3Client.createBucket(CreateBucketRequest.builder()
                    .bucket(properties.getBucket())
                    .build());
            logger.info("Created RustFS bucket: {}", properties.getBucket());
        } catch (S3Exception e) {
            if (e.statusCode() == 409) {
                logger.info("RustFS bucket was created concurrently: {}", properties.getBucket());
                return;
            }
            throw new ObjectStorageException("创建 RustFS bucket 失败: " + e.getMessage(), e);
        }
    }

    private String generateDocumentKey(String fileName) {
        String safeName = sanitizeFilename(fileName);
        String datePath = LocalDate.now().format(DATE_PATH);
        String uniqueId = UUID.randomUUID().toString().substring(0, 12);
        return "documents/" + datePath + "/" + uniqueId + "_" + safeName;
    }

    private String sanitizeFilename(String fileName) {
        if (fileName == null || fileName.isBlank()) {
            return "unknown";
        }
        String normalized = fileName.strip().replace("\\", "_");
        StringBuilder result = new StringBuilder(normalized.length());
        for (int i = 0; i < normalized.length(); i++) {
            char current = normalized.charAt(i);
            if (Character.isLetterOrDigit(current) || current == '.' || current == '_' || current == '-') {
                result.append(Character.toLowerCase(current));
            } else {
                result.append('_');
            }
        }
        String safe = result.toString();
        return safe.isBlank() || safe.chars().allMatch(value -> value == '_') ? "unknown" : safe;
    }

    private void requireKey(String key) {
        if (key == null || key.isBlank()) {
            throw new ObjectStorageException("对象存储 Key 不能为空");
        }
    }
}
