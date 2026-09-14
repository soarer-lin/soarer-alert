package org.example.controller;

import org.example.dto.FileUploadRes;
import org.example.dto.ValidatedDocument;
import org.example.entity.OpsDocument;
import org.example.service.document.ContentHashService;
import org.example.service.document.DocumentProcessingException;
import org.example.service.document.DocumentValidationService;
import org.example.service.auth.AiQuotaExhaustedException;
import org.example.service.auth.AiQuotaService;
import org.example.service.auth.AuthUserService;
import org.example.service.persistence.DocumentPersistenceService;
import org.example.service.storage.ObjectStorageService;
import org.example.service.stream.DocumentIndexStreamPublisher;
import lombok.Getter;
import lombok.Setter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.Optional;
import java.util.UUID;

/**
 * Uploads operations knowledge documents and indexes their text content.
 */
@RestController
@RequestMapping("/api")
public class FileUploadController {

    private static final Logger logger = LoggerFactory.getLogger(FileUploadController.class);

    private final DocumentValidationService validationService;
    private final ContentHashService hashService;
    private final DocumentPersistenceService documentPersistenceService;
    private final ObjectStorageService objectStorageService;
    private final DocumentIndexStreamPublisher streamPublisher;
    private final AuthUserService authUserService;
    private final AiQuotaService aiQuotaService;

    public FileUploadController(
            DocumentValidationService validationService,
            ContentHashService hashService,
            DocumentPersistenceService documentPersistenceService,
            ObjectStorageService objectStorageService,
            DocumentIndexStreamPublisher streamPublisher,
            AuthUserService authUserService,
            AiQuotaService aiQuotaService
    ) {
        this.validationService = validationService;
        this.hashService = hashService;
        this.documentPersistenceService = documentPersistenceService;
        this.objectStorageService = objectStorageService;
        this.streamPublisher = streamPublisher;
        this.authUserService = authUserService;
        this.aiQuotaService = aiQuotaService;
    }

    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ApiResponse<FileUploadRes>> upload(@RequestParam("file") MultipartFile file) {
        UUID createdBy = authUserService.currentUserId().orElse(null);
        ValidatedDocument validatedDocument = validationService.validate(file);
        byte[] bytes = readBytes(file);
        String contentHash = hashService.sha256(bytes);

        Optional<OpsDocument> existing = documentPersistenceService.findExisting(contentHash);
        OpsDocument document;
        String storageKey;
        boolean retryingFailedDocument = false;

        if (existing.isPresent()) {
            OpsDocument record = existing.get();
            if (!DocumentPersistenceService.STATUS_FAILED.equals(record.getStatus())) {
                FileUploadRes response = new FileUploadRes(
                        record.getId() == null ? null : record.getId().toString(),
                        record.getFileName(),
                        null,
                        validatedDocument.getSize()
                );
                response.setStorageKey(record.getStorageKey());
                if (record.getStorageKey() != null) {
                    response.setStorageUrl(objectStorageService.getObjectUrl(record.getStorageKey()));
                }
                response.setDetectedContentType(validatedDocument.getMediaType());
                response.setContentHash(contentHash);
                response.setChunkCount(0);
                response.setDuplicate(true);
                response.setIndexingStatus("SKIPPED_DUPLICATE");
                logger.info("Skipped duplicate document: {} ({})", record.getFileName(), contentHash);
                return ResponseEntity.ok(ApiResponse.success(response));
            }
            if (record.getStorageKey() == null || record.getStorageKey().isBlank()) {
                throw new IllegalStateException("失败文档缺少对象存储键，无法重试索引");
            }

            document = record;
            storageKey = record.getStorageKey();
            retryingFailedDocument = true;
            logger.info("Retrying failed document: {} ({})", record.getFileName(), contentHash);
        } else {
            storageKey = objectStorageService.uploadDocument(
                    bytes,
                    validatedDocument.getFileName(),
                    validatedDocument.getMediaType()
            );
            document = documentPersistenceService.createUploadedDocument(
                    validatedDocument,
                    contentHash,
                    storageKey,
                    objectStorageService.getObjectUrl(storageKey),
                    createdBy
            );
        }
        FileUploadRes response = new FileUploadRes(
                document.getId().toString(),
                validatedDocument.getFileName(),
                null,
                validatedDocument.getSize()
        );
        response.setStorageKey(document.getStorageKey());
        response.setStorageUrl(document.getStorageUrl());
        response.setDetectedContentType(validatedDocument.getMediaType());
        response.setContentHash(contentHash);
        response.setDuplicate(retryingFailedDocument);

        aiQuotaService.consume(createdBy);
        try {
            documentPersistenceService.markQueued(document.getId());
            streamPublisher.enqueue(document.getId(), contentHash);

            response.setChunkCount(0);
            response.setIndexingStatus(DocumentPersistenceService.STATUS_QUEUED);
            return ResponseEntity.ok(ApiResponse.success(response));
        } catch (Exception e) {
            aiQuotaService.refund(createdBy);
            logger.error("Failed to enqueue document indexing task: {}", storageKey, e);
            documentPersistenceService.markIndexingFailed(document.getId(), e.getMessage());
            response.setChunkCount(0);
            response.setIndexingStatus("FAILED");
            response.setFailureReason(e.getMessage());
            return ResponseEntity.ok(
                    ApiResponse.failure(500, "文件已保存到对象存储，但索引失败", response)
            );
        }
    }

    @GetMapping("/documents/{documentId}")
    public ResponseEntity<ApiResponse<DocumentStatusResponse>> getDocumentStatus(
            @PathVariable("documentId") UUID documentId
    ) {
        OpsDocument document = documentPersistenceService.getDocument(documentId);
        DocumentStatusResponse response = new DocumentStatusResponse();
        response.setDocumentId(document.getId().toString());
        response.setFileName(document.getFileName());
        response.setContentHash(document.getContentHash());
        response.setStorageKey(document.getStorageKey());
        response.setIndexingStatus(document.getStatus());
        response.setChunkCount(documentPersistenceService.getChunkCount(document.getId()));
        response.setFailureReason(document.getFailureReason());
        response.setCreatedAt(document.getCreatedAt());
        response.setIndexedAt(document.getIndexedAt());
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    @ExceptionHandler(DocumentProcessingException.class)
    public ResponseEntity<ApiResponse<FileUploadRes>> handleDocumentProcessing(
            DocumentProcessingException exception
    ) {
        return ResponseEntity.badRequest().body(ApiResponse.error(400, exception.getMessage()));
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ApiResponse<FileUploadRes>> handleMaxUploadSize(
            MaxUploadSizeExceededException exception
    ) {
        return ResponseEntity.badRequest().body(
                ApiResponse.error(400, "文件大小超过 50MB 限制")
        );
    }

    @ExceptionHandler(AiQuotaExhaustedException.class)
    public ResponseEntity<ApiResponse<FileUploadRes>> handleAiQuotaExhausted(
            AiQuotaExhaustedException exception
    ) {
        return ResponseEntity.status(429).body(ApiResponse.error(429, exception.getMessage()));
    }

    private byte[] readBytes(MultipartFile file) {
        try {
            byte[] bytes = file.getBytes();
            if (bytes.length == 0) {
                throw new DocumentProcessingException("文件不能为空");
            }
            return bytes;
        } catch (IOException e) {
            throw new DocumentProcessingException("读取上传文件失败: " + e.getMessage(), e);
        }
    }

    @Getter
    @Setter
    public static class ApiResponse<T> {
        private int code;
        private String message;
        private T data;

        public static <T> ApiResponse<T> success(T data) {
            ApiResponse<T> response = new ApiResponse<>();
            response.setCode(200);
            response.setMessage("success");
            response.setData(data);
            return response;
        }

        public static <T> ApiResponse<T> error(int code, String message) {
            ApiResponse<T> response = new ApiResponse<>();
            response.setCode(code);
            response.setMessage(message);
            return response;
        }

        public static <T> ApiResponse<T> failure(int code, String message, T data) {
            ApiResponse<T> response = new ApiResponse<>();
            response.setCode(code);
            response.setMessage(message);
            response.setData(data);
            return response;
        }
    }

    @Getter
    @Setter
    public static class DocumentStatusResponse {
        private String documentId;
        private String fileName;
        private String contentHash;
        private String storageKey;
        private String indexingStatus;
        private long chunkCount;
        private String failureReason;
        private java.time.LocalDateTime createdAt;
        private java.time.LocalDateTime indexedAt;
    }
}
