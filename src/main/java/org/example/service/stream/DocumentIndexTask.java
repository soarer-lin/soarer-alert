package org.example.service.stream;

import java.util.Map;
import java.util.UUID;

public record DocumentIndexTask(UUID documentId, String contentHash, int attempt) {

    public static DocumentIndexTask from(Map<String, String> fields) {
        String documentId = fields.get("documentId");
        String contentHash = fields.get("contentHash");
        if (documentId == null || documentId.isBlank()) {
            throw new IllegalArgumentException("Redis Stream message is missing documentId");
        }
        if (contentHash == null || contentHash.isBlank()) {
            throw new IllegalArgumentException("Redis Stream message is missing contentHash");
        }
        int attempt;
        try {
            attempt = Integer.parseInt(fields.getOrDefault("attempt", "1"));
        } catch (NumberFormatException e) {
            attempt = 1;
        }
        if (attempt < 1) {
            attempt = 1;
        }
        return new DocumentIndexTask(UUID.fromString(documentId), contentHash, attempt);
    }

    public int nextAttempt() {
        return attempt + 1;
    }
}
