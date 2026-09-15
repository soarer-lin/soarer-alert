package com.soarer.alert.service.ops;

/**
 * DocumentNotFoundException 业务服务。
 */
public class DocumentNotFoundException extends RuntimeException {

    public DocumentNotFoundException(String message) {
        super(message);
    }
}
