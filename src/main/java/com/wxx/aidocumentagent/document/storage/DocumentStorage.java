package com.wxx.aidocumentagent.document.storage;

import java.io.InputStream;

/**
 * 保存文档原始字节流，不将文档流程耦合到特定存储服务。
 */
public interface DocumentStorage {

    StoredObject store(InputStream input, String extension, long size);

    InputStream load(String storageKey);

    void delete(String storageKey);
}
