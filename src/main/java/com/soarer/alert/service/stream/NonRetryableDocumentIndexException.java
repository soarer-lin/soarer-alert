package com.soarer.alert.service.stream;

/**
 * NonRetryableDocumentIndexException 业务服务。
 */
public class NonRetryableDocumentIndexException extends RuntimeException {

    public NonRetryableDocumentIndexException(String message) {
        super(message);
    }
}
