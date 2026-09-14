package com.soarer.alert.service.ops;

public class DocumentAccessDeniedException extends RuntimeException {

    public DocumentAccessDeniedException(String message) {
        super(message);
    }
}
