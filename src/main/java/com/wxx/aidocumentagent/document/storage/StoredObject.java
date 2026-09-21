package com.wxx.aidocumentagent.document.storage;

/**
 * 成功保存后由服务端生成的对象标识。
 */
public record StoredObject(String storageKey, long sizeBytes) {
}
