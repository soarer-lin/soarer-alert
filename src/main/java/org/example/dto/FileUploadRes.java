package org.example.dto;

import lombok.Getter;
import lombok.Setter;

@Setter
@Getter
public class FileUploadRes {

    private String documentId;
    private String fileName;
    private String filePath;
    private String storageKey;
    private String storageUrl;
    private Long fileSize;
    private String detectedContentType;
    private String contentHash;
    private Integer chunkCount;
    private boolean duplicate;
    private String indexingStatus;
    private String failureReason;

    public FileUploadRes() {
    }

    public FileUploadRes(String documentId, String fileName, String filePath, Long fileSize) {
        this.documentId = documentId;
        this.fileName = fileName;
        this.filePath = filePath;
        this.fileSize = fileSize;
    }

}
