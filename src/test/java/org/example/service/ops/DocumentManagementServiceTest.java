package org.example.service.ops;

import org.example.entity.AuthUser;
import org.example.entity.OpsDocument;
import org.example.service.VectorIndexService;
import org.example.service.persistence.DocumentPersistenceService;
import org.example.service.storage.ObjectStorageService;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DocumentManagementServiceTest {

    private static final UUID DOCUMENT_ID = UUID.randomUUID();
    private static final String STORAGE_KEY = "documents/2026/09/14/runbook.md";

    private final DocumentPersistenceService persistenceService = mock(DocumentPersistenceService.class);
    private final VectorIndexService vectorIndexService = mock(VectorIndexService.class);
    private final ObjectStorageService objectStorageService = mock(ObjectStorageService.class);
    private final DocumentManagementService service = new DocumentManagementService(
            persistenceService,
            vectorIndexService,
            objectStorageService
    );

    @Test
    void loadContentReadsObjectStorageDocument() {
        OpsDocument document = document(UUID.randomUUID());
        when(persistenceService.getDocument(DOCUMENT_ID)).thenReturn(document);
        when(objectStorageService.downloadObject(STORAGE_KEY)).thenReturn("runbook".getBytes());

        DocumentManagementService.DocumentContent content = service.loadContent(DOCUMENT_ID);

        assertThat(content.document()).isSameAs(document);
        assertThat(content.content()).isEqualTo("runbook".getBytes());
    }

    @Test
    void deleteDocumentCleansVectorsDatabaseAndObjectStorageForAdmin() {
        AuthUser admin = user(AuthUser.ROLE_ADMIN, UUID.randomUUID());
        OpsDocument document = document(UUID.randomUUID());
        when(persistenceService.getDocument(DOCUMENT_ID)).thenReturn(document);

        service.deleteDocument(DOCUMENT_ID, admin);

        verify(vectorIndexService).deleteDocumentVectors(STORAGE_KEY);
        verify(persistenceService).deleteDocument(document);
        verify(objectStorageService).deleteObject(STORAGE_KEY);
    }

    @Test
    void deleteDocumentRejectsOpsUserForAdminUploadedDocument() {
        OpsDocument document = document(UUID.randomUUID());
        AuthUser opsUser = user(AuthUser.ROLE_OPS, UUID.randomUUID());
        when(persistenceService.getDocument(DOCUMENT_ID)).thenReturn(document);

        assertThatThrownBy(() -> service.deleteDocument(DOCUMENT_ID, opsUser))
                .isInstanceOf(DocumentAccessDeniedException.class)
                .hasMessage("无权删除该文档");

        verify(vectorIndexService, never()).deleteDocumentVectors(STORAGE_KEY);
        verify(persistenceService, never()).deleteDocument(document);
        verify(objectStorageService, never()).deleteObject(STORAGE_KEY);
    }

    @Test
    void deleteDocumentRejectsMissingDocument() {
        when(persistenceService.getDocument(DOCUMENT_ID))
                .thenThrow(new IllegalArgumentException("文档记录不存在: " + DOCUMENT_ID));

        assertThatThrownBy(() -> service.deleteDocument(DOCUMENT_ID, user(AuthUser.ROLE_ADMIN, UUID.randomUUID())))
                .isInstanceOf(DocumentNotFoundException.class)
                .hasMessage("文档不存在: " + DOCUMENT_ID);
    }

    @Test
    void deleteDocumentDoesNotRemoveLocalSourceFile() {
        AuthUser uploader = user(AuthUser.ROLE_OPS, UUID.randomUUID());
        OpsDocument document = document(uploader.getId());
        document.setStorageKey(null);
        document.setFilePath("D:/data/runbook.md");
        when(persistenceService.getDocument(DOCUMENT_ID)).thenReturn(document);

        service.deleteDocument(DOCUMENT_ID, uploader);

        verify(vectorIndexService).deleteDocumentVectors("D:/data/runbook.md");
        verify(persistenceService).deleteDocument(document);
        verify(objectStorageService, never()).deleteObject(STORAGE_KEY);
    }

    private OpsDocument document(UUID createdBy) {
        OpsDocument document = new OpsDocument();
        document.setId(DOCUMENT_ID);
        document.setFileName("runbook.md");
        document.setStorageKey(STORAGE_KEY);
        document.setContentHash("hash-1");
        document.setMediaType("text/markdown");
        document.setByteSize(16L);
        document.setStatus(DocumentPersistenceService.STATUS_SUCCESS);
        document.setDocumentVersion(1);
        document.setCreatedBy(createdBy);
        return document;
    }

    private AuthUser user(String role, UUID id) {
        AuthUser user = new AuthUser();
        user.setId(id);
        user.setUsername("user-" + id);
        user.setRole(role);
        user.setStatus(AuthUser.STATUS_ACTIVE);
        return user;
    }
}
