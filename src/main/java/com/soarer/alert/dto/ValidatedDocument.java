package com.soarer.alert.dto;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class ValidatedDocument {

    private String fileName;
    private String extension;
    private String mediaType;
    private long size;

    public ValidatedDocument() {
    }

    public ValidatedDocument(String fileName, String extension, String mediaType, long size) {
        this.fileName = fileName;
        this.extension = extension;
        this.mediaType = mediaType;
        this.size = size;
    }
}
