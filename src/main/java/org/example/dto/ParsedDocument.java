package org.example.dto;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class ParsedDocument {

    private String fileName;
    private String mediaType;
    private String content;
    private String contentHash;
    private long byteSize;
    private boolean utf8Bom;

    public ParsedDocument() {
    }

    public ParsedDocument(
            String fileName,
            String mediaType,
            String content,
            long byteSize,
            boolean utf8Bom
    ) {
        this.fileName = fileName;
        this.mediaType = mediaType;
        this.content = content;
        this.byteSize = byteSize;
        this.utf8Bom = utf8Bom;
    }
}
