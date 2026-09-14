package org.example.service;

import org.example.dto.DocumentChunk;
import org.example.dto.ParsedDocument;
import org.example.service.document.ContentHashService;
import org.example.service.document.DocumentParseService;
import org.example.service.document.DocumentValidationService;
import org.example.service.persistence.DocumentPersistenceService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class VectorIndexServiceObjectKeyTest {

    @Test
    void indexesObjectKeyAsDocumentSource() {
        VectorStore vectorStore = mock(VectorStore.class);
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        DocumentChunkService chunkService = mock(DocumentChunkService.class);
        DocumentValidationService validationService = mock(DocumentValidationService.class);
        DocumentParseService parseService = mock(DocumentParseService.class);
        ContentHashService hashService = mock(ContentHashService.class);
        DocumentPersistenceService documentPersistenceService = mock(DocumentPersistenceService.class);
        when(jdbcTemplate.update(anyString(), any(Object[].class))).thenReturn(0);

        VectorIndexService service = new VectorIndexService(
                vectorStore,
                jdbcTemplate,
                chunkService,
                validationService,
                parseService,
                hashService,
                documentPersistenceService,
                "./uploads"
        );

        ParsedDocument parsedDocument = new ParsedDocument(
                "runbook.md",
                "text/markdown",
                "restart service",
                16L,
                false
        );
        parsedDocument.setContentHash("hash-1");
        DocumentChunk chunk = new DocumentChunk("restart service", 0, 16, 0);

        service.indexDocument(
                parsedDocument,
                "documents/2026/09/03/123456789012_runbook.md",
                List.of(chunk)
        );

        ArgumentCaptor<List<Document>> documentsCaptor = ArgumentCaptor.forClass((Class) List.class);
        verify(vectorStore).add(documentsCaptor.capture());
        List<Document> documents = documentsCaptor.getValue();
        assertThat(documents).hasSize(1);
        assertThat(documents.get(0).getId()).matches(
                "^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$"
        );
        assertThat(documents.get(0).getText()).isEqualTo("restart service");
        assertThat(documents.get(0).getMetadata())
                .containsEntry("_source", "documents/2026/09/03/123456789012_runbook.md")
                .containsEntry("_file_name", "123456789012_runbook.md")
                .containsEntry("_content_hash", "hash-1");
    }
}
