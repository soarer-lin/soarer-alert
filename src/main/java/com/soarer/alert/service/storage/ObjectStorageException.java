package com.soarer.alert.service.storage;

/**
 * ObjectStorageException 业务服务。
 */
public class ObjectStorageException extends RuntimeException {

    public ObjectStorageException(String message) {
        super(message);
    }

    public ObjectStorageException(String message, Throwable cause) {
        super(message, cause);
    }
}
