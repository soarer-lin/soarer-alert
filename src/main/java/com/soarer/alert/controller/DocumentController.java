package com.soarer.alert.controller;

import com.soarer.alert.dto.ops.ApiResult;
import com.soarer.alert.entity.AuthUser;
import com.soarer.alert.service.auth.AuthUserService;
import com.soarer.alert.service.ops.DocumentManagementService;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.UUID;

@RestController
@RequestMapping("/api/documents")
public class DocumentController {

    private final DocumentManagementService managementService;
    private final AuthUserService authUserService;

    public DocumentController(
            DocumentManagementService managementService,
            AuthUserService authUserService
    ) {
        this.managementService = managementService;
        this.authUserService = authUserService;
    }

    @GetMapping("/{documentId}/content")
    public ResponseEntity<byte[]> content(@PathVariable UUID documentId) {
        DocumentManagementService.DocumentContent content = managementService.loadContent(documentId);
        return ResponseEntity.ok()
                .contentType(contentMediaType(content.document().getMediaType()))
                .contentLength(content.content().length)
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.inline()
                        .filename(content.document().getFileName(), StandardCharsets.UTF_8)
                        .build()
                        .toString())
                .header("X-Content-Type-Options", "nosniff")
                .body(content.content());
    }

    @DeleteMapping("/{documentId}")
    public ApiResult<Void> delete(@PathVariable UUID documentId) {
        AuthUser currentUser = authUserService.currentUser()
                .orElseThrow(() -> new IllegalStateException("当前登录用户不存在"));
        managementService.deleteDocument(documentId, currentUser);
        return ApiResult.success(null);
    }

    private MediaType contentMediaType(String mediaType) {
        String normalized = mediaType == null ? "" : mediaType.toLowerCase(Locale.ROOT);
        if ("application/x-tika-msoffice".equals(normalized)) {
            return MediaType.parseMediaType("application/msword");
        }
        try {
            MediaType parsed = MediaType.parseMediaType(normalized);
            if ("text".equalsIgnoreCase(parsed.getType())) {
                return new MediaType(parsed, StandardCharsets.UTF_8);
            }
            return parsed;
        } catch (Exception e) {
            return MediaType.APPLICATION_OCTET_STREAM;
        }
    }
}
