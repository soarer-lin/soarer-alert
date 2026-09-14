package org.example.controller;

import org.example.dto.ops.ApiResult;
import org.example.service.auth.AiQuotaExhaustedException;
import org.example.service.ops.DocumentAccessDeniedException;
import org.example.service.ops.DocumentNotFoundException;
import org.example.service.ops.DiagnosisRunNotFoundException;
import org.example.service.ops.ReportNotFoundException;
import org.example.service.storage.ObjectStorageException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class OpsControllerAdvice {

    @ExceptionHandler({DiagnosisRunNotFoundException.class, ReportNotFoundException.class, DocumentNotFoundException.class})
    public ResponseEntity<ApiResult<Void>> handleNotFound(RuntimeException exception) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(ApiResult.error(404, exception.getMessage()));
    }

    @ExceptionHandler(DocumentAccessDeniedException.class)
    public ResponseEntity<ApiResult<Void>> handleAccessDenied(DocumentAccessDeniedException exception) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(ApiResult.error(403, exception.getMessage()));
    }

    @ExceptionHandler(ObjectStorageException.class)
    public ResponseEntity<ApiResult<Void>> handleObjectStorage(ObjectStorageException exception) {
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiResult.error(500, exception.getMessage()));
    }

    @ExceptionHandler(AiQuotaExhaustedException.class)
    public ResponseEntity<ApiResult<Void>> handleAiQuotaExhausted(AiQuotaExhaustedException exception) {
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .body(ApiResult.error(429, exception.getMessage()));
    }
}
