package com.wxx.aidocumentagent.document.storage;

/**
 * 访问文档对象存储时发生的基础设施异常。
 */
public class DocumentStorageException extends RuntimeException {

    public DocumentStorageException(String message, Throwable cause) {
        super(message, cause);
    }

    public DocumentStorageException(String message) {
        super(message);
    }
}
