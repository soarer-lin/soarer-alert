package com.soarer.alert.service.ops;

import com.soarer.alert.entity.AuthUser;
import com.soarer.alert.entity.OpsDocument;
import com.soarer.alert.service.persistence.DocumentPersistenceService;
import com.soarer.alert.service.storage.ObjectStorageException;
import com.soarer.alert.service.storage.ObjectStorageService;
import com.soarer.alert.service.VectorIndexService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

@Service
public class DocumentManagementService {

    private final DocumentPersistenceService persistenceService;
    private final VectorIndexService vectorIndexService;
    private final ObjectStorageService objectStorageService;

    public DocumentManagementService(
            DocumentPersistenceService persistenceService,
            VectorIndexService vectorIndexService,
            ObjectStorageService objectStorageService
    ) {
        this.persistenceService = persistenceService;
        this.vectorIndexService = vectorIndexService;
        this.objectStorageService = objectStorageService;
    }

    public DocumentContent loadContent(UUID documentId) {
        OpsDocument document = requireDocument(documentId);
        if (document.getStorageKey() != null && !document.getStorageKey().isBlank()) {
            return new DocumentContent(document, objectStorageService.downloadObject(document.getStorageKey()));
        }
        if (document.getFilePath() != null && !document.getFilePath().isBlank()) {
            Path path = Path.of(document.getFilePath());
            if (!Files.isRegularFile(path)) {
                throw new DocumentNotFoundException("文档原文不存在: " + document.getFileName());
            }
            try {
                return new DocumentContent(document, Files.readAllBytes(path));
            } catch (IOException e) {
                throw new ObjectStorageException("读取文档原文失败: " + e.getMessage(), e);
            }
        }
        throw new DocumentNotFoundException("文档没有可查看的原文: " + document.getFileName());
    }

    @Transactional
    public void deleteDocument(UUID documentId, AuthUser currentUser) {
        OpsDocument document = requireDocument(documentId);
        if (!canDelete(document, currentUser)) {
            throw new DocumentAccessDeniedException("无权删除该文档");
        }

        String source = document.getStorageKey() != null && !document.getStorageKey().isBlank()
                ? document.getStorageKey()
                : document.getFilePath();
        vectorIndexService.deleteDocumentVectors(source);
        persistenceService.deleteDocument(document);
        if (document.getStorageKey() != null && !document.getStorageKey().isBlank()) {
            objectStorageService.deleteObject(document.getStorageKey());
        }
    }

    private boolean canDelete(OpsDocument document, AuthUser currentUser) {
        if (currentUser == null) {
            return false;
        }
        return AuthUser.ROLE_ADMIN.equals(currentUser.getRole())
                || currentUser.getId().equals(document.getCreatedBy());
    }

    private OpsDocument requireDocument(UUID documentId) {
        try {
            return persistenceService.getDocument(documentId);
        } catch (IllegalArgumentException e) {
            throw new DocumentNotFoundException("文档不存在: " + documentId);
        }
    }

    public record DocumentContent(OpsDocument document, byte[] content) {
    }
}
