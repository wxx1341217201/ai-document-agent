package com.wxx.aidocumentagent.document.storage;

import java.nio.file.Path;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.util.unit.DataSize;

/**
 * 非敏感、可通过环境变量覆盖的文档存储配置。
 */
@Component
@ConfigurationProperties(prefix = "app.document")
public class DocumentStorageProperties {

    private Path storageRoot = Path.of("./data/documents");
    private DataSize maxFileSize = DataSize.ofMegabytes(20);

    public Path getStorageRoot() {
        return storageRoot;
    }

    public void setStorageRoot(Path storageRoot) {
        this.storageRoot = storageRoot;
    }

    public DataSize getMaxFileSize() {
        return maxFileSize;
    }

    public void setMaxFileSize(DataSize maxFileSize) {
        this.maxFileSize = maxFileSize;
    }
}
