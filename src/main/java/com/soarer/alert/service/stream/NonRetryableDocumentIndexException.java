package com.soarer.alert.service.stream;

public class NonRetryableDocumentIndexException extends RuntimeException {

    public NonRetryableDocumentIndexException(String message) {
        super(message);
    }
}
