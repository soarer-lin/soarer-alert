package com.soarer.alert.service.persistence;

import com.soarer.alert.dto.DocumentChunk;
import com.soarer.alert.dto.ValidatedDocument;
import com.soarer.alert.entity.OpsDocument;
import com.soarer.alert.repository.OpsDocumentChunkRepository;
import com.soarer.alert.repository.OpsDocumentRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DocumentPersistenceServiceTest {

    private final OpsDocumentRepository documentRepository = mock(OpsDocumentRepository.class);
    private final OpsDocumentChunkRepository chunkRepository = mock(OpsDocumentChunkRepository.class);
    private final DocumentPersistenceService service =
            new DocumentPersistenceService(documentRepository, chunkRepository);

    @Test
    void createUploadedDocumentStoresObjectStorageMetadata() {
        when(documentRepository.save(any(OpsDocument.class))).thenAnswer(invocation -> invocation.getArgument(0));
        ValidatedDocument validatedDocument = new ValidatedDocument("runbook.md", ".md", "text/markdown", 16);

        OpsDocument document = service.createUploadedDocument(
                validatedDocument,
                "hash-1",
                "documents/2026/09/04/runbook.md",
                "http://localhost:19000/superbiz-agent/documents/2026/09/04/runbook.md",
                null
        );

        assertThat(document.getFileName()).isEqualTo("runbook.md");
        assertThat(document.getStorageKey()).isEqualTo("documents/2026/09/04/runbook.md");
        assertThat(document.getContentHash()).isEqualTo("hash-1");
        assertThat(document.getMediaType()).isEqualTo("text/markdown");
        assertThat(document.getByteSize()).isEqualTo(16);
        assertThat(document.getStatus()).isEqualTo(DocumentPersistenceService.STATUS_UPLOADED);
        assertThat(document.getDocumentVersion()).isEqualTo(1);
    }

    @Test
    void markIndexingFailedStoresFailureReason() {
        UUID documentId = UUID.randomUUID();
        OpsDocument document = document(documentId);
        document.setFailureReason(null);
        when(documentRepository.findById(documentId)).thenReturn(Optional.of(document));

        service.markIndexingFailed(documentId, "embedding failed");

        assertThat(document.getStatus()).isEqualTo(DocumentPersistenceService.STATUS_FAILED);
        assertThat(document.getFailureReason()).isEqualTo("embedding failed");
        verify(documentRepository).save(document);
    }

    @Test
    void markIndexingSuccessReplacesChunksAndUpdatesStatus() {
        UUID documentId = UUID.randomUUID();
        OpsDocument document = document(documentId);
        when(documentRepository.findById(documentId)).thenReturn(Optional.of(document));

        DocumentChunk first = chunk("first", 0, "TEXT");
        DocumentChunk second = chunk("second", 1, "CODE");

        service.markIndexingSuccess(documentId, List.of(first, second));

        assertThat(document.getStatus()).isEqualTo(DocumentPersistenceService.STATUS_SUCCESS);
        assertThat(document.getFailureReason()).isNull();
        assertThat(document.getIndexedAt()).isNotNull();
        assertThat(document.getDocumentVersion()).isEqualTo(2);

        verify(chunkRepository).deleteByDocumentId(documentId);
        ArgumentCaptor<List<com.soarer.alert.entity.OpsDocumentChunk>> chunksCaptor =
                ArgumentCaptor.forClass((Class) List.class);
        verify(chunkRepository).saveAll(chunksCaptor.capture());
        List<com.soarer.alert.entity.OpsDocumentChunk> savedChunks = chunksCaptor.getValue();
        assertThat(savedChunks).hasSize(2);
        assertThat(savedChunks.get(0).getContent()).isEqualTo("first");
        assertThat(savedChunks.get(0).getChunkType()).isEqualTo("TEXT");
        assertThat(savedChunks.get(1).getContent()).isEqualTo("second");
        assertThat(savedChunks.get(1).getChunkType()).isEqualTo("CODE");
    }

    @Test
    void replaceChunksWithEmptyListDeletesOldChunksWithoutSaving() {
        UUID documentId = UUID.randomUUID();

        service.replaceChunks(documentId, List.of());

        verify(chunkRepository).deleteByDocumentId(documentId);
        verify(chunkRepository, never()).saveAll(anyList());
    }

    private OpsDocument document(UUID id) {
        OpsDocument document = new OpsDocument();
        document.setId(id);
        document.setFileName("runbook.md");
        document.setContentHash("hash-1");
        document.setMediaType("text/markdown");
        document.setByteSize(16L);
        document.setStatus(DocumentPersistenceService.STATUS_INDEXING);
        document.setDocumentVersion(1);
        return document;
    }

    private DocumentChunk chunk(String content, int index, String type) {
        DocumentChunk chunk = new DocumentChunk(content, index * 10, index * 10 + 10, index);
        chunk.setTitle("Chunk " + index);
        chunk.setChunkType(type);
        return chunk;
    }
}
