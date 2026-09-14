package com.soarer.alert.service.stream;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DocumentIndexTaskTest {

    @Test
    void parsesTaskAndDefaultsToFirstAttempt() {
        UUID documentId = UUID.randomUUID();
        Map<String, String> fields = new HashMap<>();
        fields.put("documentId", documentId.toString());
        fields.put("contentHash", "hash-1");

        DocumentIndexTask task = DocumentIndexTask.from(fields);

        assertThat(task.documentId()).isEqualTo(documentId);
        assertThat(task.contentHash()).isEqualTo("hash-1");
        assertThat(task.attempt()).isEqualTo(1);
        assertThat(task.nextAttempt()).isEqualTo(2);
    }

    @Test
    void normalizesInvalidAttemptValue() {
        Map<String, String> fields = new HashMap<>();
        fields.put("documentId", UUID.randomUUID().toString());
        fields.put("contentHash", "hash-1");
        fields.put("attempt", "not-a-number");

        assertThat(DocumentIndexTask.from(fields).attempt()).isEqualTo(1);
    }

    @Test
    void rejectsMissingDocumentId() {
        Map<String, String> fields = new HashMap<>();
        fields.put("contentHash", "hash-1");

        assertThatThrownBy(() -> DocumentIndexTask.from(fields))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("documentId");
    }

    @Test
    void rejectsMissingContentHash() {
        Map<String, String> fields = new HashMap<>();
        fields.put("documentId", UUID.randomUUID().toString());

        assertThatThrownBy(() -> DocumentIndexTask.from(fields))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("contentHash");
    }

    @Test
    void rejectsInvalidDocumentId() {
        Map<String, String> fields = new HashMap<>();
        fields.put("documentId", "not-a-uuid");
        fields.put("contentHash", "hash-1");

        assertThatThrownBy(() -> DocumentIndexTask.from(fields))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
