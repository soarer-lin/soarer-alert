package org.example.controller;

import org.example.service.storage.ObjectStorageException;
import org.example.service.storage.ObjectStorageService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/object-store")
public class ObjectStoreHealthController {

    private final ObjectStorageService storageService;

    public ObjectStoreHealthController(ObjectStorageService storageService) {
        this.storageService = storageService;
    }

    @GetMapping("/health")
    public ResponseEntity<Map<String, Object>> health() {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("bucket", storageService.getBucketName());
        try {
            storageService.ensureBucketExists();
            response.put("bucketReady", true);
            response.put("status", "ok");
            return ResponseEntity.ok(response);
        } catch (ObjectStorageException e) {
            response.put("bucketReady", false);
            response.put("status", "error");
            response.put("message", e.getMessage());
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(response);
        }
    }
}
