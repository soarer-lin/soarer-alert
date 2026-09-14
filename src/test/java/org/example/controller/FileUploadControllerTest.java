package org.example.controller;

import org.example.dto.FileUploadRes;
import org.example.dto.ValidatedDocument;
import org.example.entity.OpsDocument;
import org.example.service.document.ContentHashService;
import org.example.service.document.DocumentValidationService;
import org.example.service.auth.AiQuotaExhaustedException;
import org.example.service.auth.AiQuotaService;
import org.example.service.auth.AuthUserService;
import org.example.service.persistence.DocumentPersistenceService;
import org.example.service.storage.ObjectStorageService;
import org.example.service.stream.DocumentIndexStreamPublisher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockMultipartFile;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class FileUploadControllerTest {

    private static final UUID DOCUMENT_ID = UUID.randomUUID();
    private static final String CONTENT_HASH = "hash-1";
    private static final String STORAGE_KEY = "documents/2026/09/04/runbook.md";
    private static final String STORAGE_URL = "http://localhost:19000/superbiz-agent/documents/runbook.md";

    private final DocumentValidationService validationService = mock(DocumentValidationService.class);
    private final ContentHashService hashService = mock(ContentHashService.class);
    private final DocumentPersistenceService persistenceService = mock(DocumentPersistenceService.class);
    private final ObjectStorageService objectStorageService = mock(ObjectStorageService.class);
    private final DocumentIndexStreamPublisher streamPublisher = mock(DocumentIndexStreamPublisher.class);
    private final AuthUserService authUserService = mock(AuthUserService.class);
    private final AiQuotaService aiQuotaService = mock(AiQuotaService.class);

    private FileUploadController controller;
    private ValidatedDocument validated;
    private MockMultipartFile file;

    @BeforeEach
    void setUp() {
        controller = new FileUploadController(
                validationService,
                hashService,
                persistenceService,
                objectStorageService,
                streamPublisher,
                authUserService,
                aiQuotaService
        );
        validated = new ValidatedDocument("runbook.md", ".md", "text/markdown", 11);
        file = new MockMultipartFile("file", "runbook.md", "text/markdown", "runbook".getBytes());
        when(validationService.validate(file)).thenReturn(validated);
        when(hashService.sha256(any(byte[].class))).thenReturn(CONTENT_HASH);
    }

    @Test
    void uploadQueuesNewDocumentForAsyncIndexing() {
        OpsDocument document = document(DocumentPersistenceService.STATUS_UPLOADED);

        when(persistenceService.findExisting(CONTENT_HASH)).thenReturn(Optional.empty());
        when(objectStorageService.uploadDocument(any(), anyString(), anyString())).thenReturn(STORAGE_KEY);
        when(objectStorageService.getObjectUrl(STORAGE_KEY)).thenReturn(STORAGE_URL);
        when(persistenceService.createUploadedDocument(validated, CONTENT_HASH, STORAGE_KEY, STORAGE_URL, null))
                .thenReturn(document);

        ResponseEntity<FileUploadController.ApiResponse<FileUploadRes>> response = controller.upload(file);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        FileUploadRes body = response.getBody().getData();
        assertThat(body.getDocumentId()).isEqualTo(DOCUMENT_ID.toString());
        assertThat(body.getStorageKey()).isEqualTo(STORAGE_KEY);
        assertThat(body.getStorageUrl()).isEqualTo(STORAGE_URL);
        assertThat(body.getContentHash()).isEqualTo(CONTENT_HASH);
        assertThat(body.isDuplicate()).isFalse();
        assertThat(body.getChunkCount()).isZero();
        assertThat(body.getIndexingStatus()).isEqualTo(DocumentPersistenceService.STATUS_QUEUED);

        verify(objectStorageService).uploadDocument(any(), anyString(), anyString());
        verify(aiQuotaService).consume(null);
        verify(persistenceService).createUploadedDocument(validated, CONTENT_HASH, STORAGE_KEY, STORAGE_URL, null);
        verify(persistenceService).markQueued(DOCUMENT_ID);
        verify(streamPublisher).enqueue(DOCUMENT_ID, CONTENT_HASH);
    }

    @Test
    void uploadRetriesFailedDocumentWithoutCreatingNewStorageObject() {
        OpsDocument failedDocument = document(DocumentPersistenceService.STATUS_FAILED);

        when(persistenceService.findExisting(CONTENT_HASH)).thenReturn(Optional.of(failedDocument));

        ResponseEntity<FileUploadController.ApiResponse<FileUploadRes>> response = controller.upload(file);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        FileUploadRes body = response.getBody().getData();
        assertThat(body.getDocumentId()).isEqualTo(DOCUMENT_ID.toString());
        assertThat(body.getStorageKey()).isEqualTo(STORAGE_KEY);
        assertThat(body.isDuplicate()).isTrue();
        assertThat(body.getIndexingStatus()).isEqualTo(DocumentPersistenceService.STATUS_QUEUED);

        verify(objectStorageService, never()).uploadDocument(any(), anyString(), anyString());
        verify(aiQuotaService).consume(null);
        verify(persistenceService, never()).createUploadedDocument(any(), anyString(), anyString(), anyString(), any());
        verify(persistenceService).markQueued(DOCUMENT_ID);
        verify(streamPublisher).enqueue(DOCUMENT_ID, CONTENT_HASH);
    }

    @Test
    void uploadSkipsSuccessfulDocument() {
        OpsDocument successfulDocument = document(DocumentPersistenceService.STATUS_SUCCESS);

        when(persistenceService.findExisting(CONTENT_HASH)).thenReturn(Optional.of(successfulDocument));
        when(objectStorageService.getObjectUrl(STORAGE_KEY)).thenReturn(STORAGE_URL);

        ResponseEntity<FileUploadController.ApiResponse<FileUploadRes>> response = controller.upload(file);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        FileUploadRes body = response.getBody().getData();
        assertThat(body.isDuplicate()).isTrue();
        assertThat(body.getIndexingStatus()).isEqualTo("SKIPPED_DUPLICATE");

        verify(objectStorageService, never()).uploadDocument(any(), anyString(), anyString());
        verify(persistenceService, never()).createUploadedDocument(any(), anyString(), anyString(), anyString(), any());
        verify(persistenceService, never()).markQueued(DOCUMENT_ID);
        verify(streamPublisher, never()).enqueue(DOCUMENT_ID, CONTENT_HASH);
        verify(aiQuotaService, never()).consume(any());
    }

    @Test
    void uploadMarksDocumentFailedWhenEnqueueFails() {
        OpsDocument document = document(DocumentPersistenceService.STATUS_UPLOADED);

        when(persistenceService.findExisting(CONTENT_HASH)).thenReturn(Optional.empty());
        when(objectStorageService.uploadDocument(any(), anyString(), anyString())).thenReturn(STORAGE_KEY);
        when(objectStorageService.getObjectUrl(STORAGE_KEY)).thenReturn(STORAGE_URL);
        when(persistenceService.createUploadedDocument(validated, CONTENT_HASH, STORAGE_KEY, STORAGE_URL, null))
                .thenReturn(document);
        when(streamPublisher.enqueue(DOCUMENT_ID, CONTENT_HASH)).thenThrow(new RuntimeException("Redis unavailable"));

        ResponseEntity<FileUploadController.ApiResponse<FileUploadRes>> response = controller.upload(file);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getCode()).isEqualTo(500);
        assertThat(response.getBody().getMessage()).isEqualTo("文件已保存到对象存储，但索引失败");
        FileUploadRes body = response.getBody().getData();
        assertThat(body.getIndexingStatus()).isEqualTo(DocumentPersistenceService.STATUS_FAILED);
        assertThat(body.getFailureReason()).isEqualTo("Redis unavailable");

        verify(persistenceService).markQueued(DOCUMENT_ID);
        verify(persistenceService).markIndexingFailed(DOCUMENT_ID, "Redis unavailable");
        verify(aiQuotaService).refund(null);
    }

    @Test
    void uploadRejectsQuotaExhaustedBeforeIndexing() {
        OpsDocument document = document(DocumentPersistenceService.STATUS_UPLOADED);

        when(persistenceService.findExisting(CONTENT_HASH)).thenReturn(Optional.empty());
        when(objectStorageService.uploadDocument(any(), anyString(), anyString())).thenReturn(STORAGE_KEY);
        when(objectStorageService.getObjectUrl(STORAGE_KEY)).thenReturn(STORAGE_URL);
        when(persistenceService.createUploadedDocument(validated, CONTENT_HASH, STORAGE_KEY, STORAGE_URL, null))
                .thenReturn(document);
        doThrow(new AiQuotaExhaustedException()).when(aiQuotaService).consume(null);

        assertThatThrownBy(() -> controller.upload(file))
                .isInstanceOf(AiQuotaExhaustedException.class)
                .hasMessage("额度已耗尽，请联系管理员重置~");
        verify(streamPublisher, never()).enqueue(DOCUMENT_ID, CONTENT_HASH);
    }

    @Test
    void getDocumentStatusReturnsCurrentIndexingState() {
        OpsDocument document = document(DocumentPersistenceService.STATUS_INDEXING);
        document.setFailureReason("previous failure");

        when(persistenceService.getDocument(DOCUMENT_ID)).thenReturn(document);
        when(persistenceService.getChunkCount(DOCUMENT_ID)).thenReturn(27L);

        ResponseEntity<FileUploadController.ApiResponse<FileUploadController.DocumentStatusResponse>> response =
                controller.getDocumentStatus(DOCUMENT_ID);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        FileUploadController.DocumentStatusResponse body = response.getBody().getData();
        assertThat(body.getDocumentId()).isEqualTo(DOCUMENT_ID.toString());
        assertThat(body.getIndexingStatus()).isEqualTo(DocumentPersistenceService.STATUS_INDEXING);
        assertThat(body.getChunkCount()).isEqualTo(27L);
        assertThat(body.getFailureReason()).isEqualTo("previous failure");
    }

    private OpsDocument document(String status) {
        OpsDocument document = new OpsDocument();
        document.setId(DOCUMENT_ID);
        document.setFileName("runbook.md");
        document.setStorageKey(STORAGE_KEY);
        document.setStorageUrl(STORAGE_URL);
        document.setContentHash(CONTENT_HASH);
        document.setMediaType("text/markdown");
        document.setByteSize(11L);
        document.setStatus(status);
        document.setDocumentVersion(1);
        return document;
    }
}
