package com.wxx.aidocumentagent.document.storage;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LocalDocumentStorageTest {

    @TempDir
    java.nio.file.Path temporaryDirectory;

    @Test
    void 使用服务端生成的存储键保存字节() throws Exception {
        LocalDocumentStorage storage = storage();
        byte[] content = "文档内容".getBytes(StandardCharsets.UTF_8);

        StoredObject storedObject = storage.store(new ByteArrayInputStream(content), "txt", content.length);

        assertThat(storedObject.storageKey())
                .matches("^[0-9a-f-]{36}\\.txt$")
                .doesNotContain("内容");
        try (InputStream loaded = storage.load(storedObject.storageKey())) {
            assertThat(loaded.readAllBytes()).isEqualTo(content);
        }
        storage.delete(storedObject.storageKey());
        assertThatThrownBy(() -> storage.load(storedObject.storageKey()))
                .isInstanceOf(DocumentStorageException.class);
    }

    @Test
    void 拒绝路径穿越和绝对路径存储键() {
        LocalDocumentStorage storage = storage();

        assertThatThrownBy(() -> storage.load("../secret.pdf"))
                .isInstanceOf(DocumentStorageException.class);
        assertThatThrownBy(() -> storage.delete("C:\\secret.pdf"))
                .isInstanceOf(DocumentStorageException.class);
    }

    private LocalDocumentStorage storage() {
        DocumentStorageProperties properties = new DocumentStorageProperties();
        properties.setStorageRoot(temporaryDirectory);
        return new LocalDocumentStorage(properties);
    }
}
