package com.soarer.alert.service.persistence;

import com.soarer.alert.dto.DocumentChunk;
import com.soarer.alert.dto.ValidatedDocument;
import com.soarer.alert.entity.OpsDocument;
import com.soarer.alert.entity.OpsDocumentChunk;
import com.soarer.alert.repository.OpsDocumentChunkRepository;
import com.soarer.alert.repository.OpsDocumentRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
public class DocumentPersistenceService {

    public static final String STATUS_UPLOADED = "UPLOADED";
    public static final String STATUS_QUEUED = "QUEUED";
    public static final String STATUS_INDEXING = "INDEXING";
    public static final String STATUS_RETRYING = "RETRYING";
    public static final String STATUS_SUCCESS = "SUCCESS";
    public static final String STATUS_FAILED = "FAILED";

    private final OpsDocumentRepository documentRepository;
    private final OpsDocumentChunkRepository chunkRepository;

    public DocumentPersistenceService(
            OpsDocumentRepository documentRepository,
            OpsDocumentChunkRepository chunkRepository
    ) {
        this.documentRepository = documentRepository;
        this.chunkRepository = chunkRepository;
    }

    @Transactional
    public Optional<OpsDocument> findExisting(String contentHash) {
        Optional<OpsDocument> existing = documentRepository.findByContentHash(contentHash);
        if (existing.isEmpty()) {
            return Optional.empty();
        }

        OpsDocument document = existing.get();
        if (document.getStorageKey() != null && !document.getStorageKey().isBlank()) {
            return existing;
        }
        if (document.getFilePath() != null && !document.getFilePath().isBlank()) {
            return Files.isRegularFile(Path.of(document.getFilePath()))
                    ? existing
                    : Optional.empty();
        }

        documentRepository.delete(document);
        return Optional.empty();
    }

    @Transactional
    public OpsDocument createUploadedDocument(
            ValidatedDocument validatedDocument,
            String contentHash,
            String storageKey,
            String storageUrl,
            UUID createdBy
    ) {
        OpsDocument document = new OpsDocument();
        document.setFileName(validatedDocument.getFileName());
        document.setStorageKey(storageKey);
        document.setStorageUrl(storageUrl);
        document.setContentHash(contentHash);
        document.setMediaType(validatedDocument.getMediaType());
        document.setByteSize(validatedDocument.getSize());
        document.setStatus(STATUS_UPLOADED);
        document.setDocumentVersion(1);
        document.setCreatedBy(createdBy);
        return documentRepository.save(document);
    }

    @Transactional
    public OpsDocument registerLocalDocument(
            ValidatedDocument validatedDocument,
            String contentHash,
            Path filePath,
            UUID createdBy
    ) {
        Optional<OpsDocument> existing = findExisting(contentHash);
        if (existing.isPresent()) {
            return existing.get();
        }

        OpsDocument document = new OpsDocument();
        document.setFileName(validatedDocument.getFileName());
        document.setFilePath(filePath.normalize().toString());
        document.setContentHash(contentHash);
        document.setMediaType(validatedDocument.getMediaType());
        document.setByteSize(validatedDocument.getSize());
        document.setStatus(STATUS_UPLOADED);
        document.setDocumentVersion(1);
        document.setCreatedBy(createdBy);
        return documentRepository.save(document);
    }

    @Transactional
    public void markIndexing(UUID documentId) {
        OpsDocument document = requireDocument(documentId);
        document.setStatus(STATUS_INDEXING);
        document.setFailureReason(null);
        documentRepository.save(document);
    }

    @Transactional
    public void markQueued(UUID documentId) {
        OpsDocument document = requireDocument(documentId);
        document.setStatus(STATUS_QUEUED);
        document.setFailureReason(null);
        documentRepository.save(document);
    }

    @Transactional
    public void markIndexingRetrying(UUID documentId, int nextAttempt, String failureReason) {
        OpsDocument document = requireDocument(documentId);
        document.setStatus(STATUS_RETRYING);
        document.setFailureReason("第" + nextAttempt + "次尝试失败: " + failureReason);
        documentRepository.save(document);
    }

    @Transactional(readOnly = true)
    public OpsDocument getDocument(UUID documentId) {
        return requireDocument(documentId);
    }

    @Transactional
    public void deleteDocument(OpsDocument document) {
        documentRepository.delete(document);
    }

    @Transactional(readOnly = true)
    public long getChunkCount(UUID documentId) {
        return chunkRepository.countByDocumentId(documentId);
    }

    @Transactional
    public void markIndexingFailed(UUID documentId, String failureReason) {
        OpsDocument document = requireDocument(documentId);
        document.setStatus(STATUS_FAILED);
        document.setFailureReason(failureReason);
        documentRepository.save(document);
    }

    @Transactional
    public void markIndexingSuccess(UUID documentId, List<DocumentChunk> chunks) {
        OpsDocument document = requireDocument(documentId);
        replaceChunks(documentId, chunks);
        document.setStatus(STATUS_SUCCESS);
        document.setFailureReason(null);
        document.setIndexedAt(java.time.LocalDateTime.now());
        document.setDocumentVersion(document.getDocumentVersion() + 1);
        documentRepository.save(document);
    }

    @Transactional
    public void replaceChunks(UUID documentId, List<DocumentChunk> chunks) {
        chunkRepository.deleteByDocumentId(documentId);
        if (chunks == null || chunks.isEmpty()) {
            return;
        }

        List<OpsDocumentChunk> entities = new ArrayList<>(chunks.size());
        for (DocumentChunk chunk : chunks) {
            OpsDocumentChunk entity = new OpsDocumentChunk();
            entity.setDocumentId(documentId);
            entity.setChunkIndex(chunk.getChunkIndex());
            entity.setTitle(chunk.getTitle());
            entity.setChunkType(chunk.getChunkType());
            entity.setStartIndex(chunk.getStartIndex());
            entity.setEndIndex(chunk.getEndIndex());
            entity.setContent(chunk.getContent());
            entities.add(entity);
        }
        chunkRepository.saveAll(entities);
    }

    private OpsDocument requireDocument(UUID documentId) {
        return documentRepository.findById(documentId)
                .orElseThrow(() -> new IllegalArgumentException("文档记录不存在: " + documentId));
    }
}
