package org.example.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Checks the PostgreSQL vector store used by the knowledge index.
 */
@RestController
@RequestMapping("/vector-store")
public class VectorStoreHealthController {

    private final JdbcTemplate jdbcTemplate;

    public VectorStoreHealthController(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @GetMapping("/health")
    public ResponseEntity<Map<String, Object>> health() {
        Map<String, Object> result = new LinkedHashMap<>();
        try {
            jdbcTemplate.queryForObject("SELECT 1", Integer.class);
            boolean extensionReady = queryBoolean(
                    "SELECT EXISTS (SELECT 1 FROM pg_extension WHERE extname = 'vector')"
            );
            boolean tableReady = queryBoolean(
                    "SELECT to_regclass('public.vector_store') IS NOT NULL"
            );
            boolean indexReady = queryBoolean(
                    "SELECT to_regclass('public.spring_ai_vector_index') IS NOT NULL"
            );

            result.put("database", "ok");
            result.put("extensionReady", extensionReady);
            result.put("tableReady", tableReady);
            result.put("indexReady", indexReady);

            if (extensionReady && tableReady && indexReady) {
                result.put("status", "ok");
                return ResponseEntity.ok(result);
            }

            result.put("status", "unhealthy");
            return ResponseEntity.status(503).body(result);
        } catch (Exception e) {
            result.put("database", "unavailable");
            result.put("status", "unhealthy");
            result.put("error", e.getMessage());
            return ResponseEntity.status(503).body(result);
        }
    }

    private boolean queryBoolean(String sql) {
        return Boolean.TRUE.equals(jdbcTemplate.queryForObject(sql, Boolean.class));
    }
}
