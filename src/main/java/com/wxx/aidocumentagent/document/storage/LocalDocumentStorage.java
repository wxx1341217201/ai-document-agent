package com.wxx.aidocumentagent.document.storage;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.UUID;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

/**
 * 仅接受服务端生成存储键的本地磁盘存储实现。
 */
@Component
public class LocalDocumentStorage implements DocumentStorage {

    private static final Pattern EXTENSION_PATTERN = Pattern.compile("^(pdf|docx|txt)$");
    private static final Pattern STORAGE_KEY_PATTERN =
            Pattern.compile("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\.(pdf|docx|txt)$");

    private final Path storageRoot;

    public LocalDocumentStorage(DocumentStorageProperties properties) {
        this.storageRoot = properties.getStorageRoot().toAbsolutePath().normalize();
    }

    @Override
    public StoredObject store(InputStream input, String extension, long size) {
        if (input == null || size < 0 || !EXTENSION_PATTERN.matcher(extension).matches()) {
            throw new DocumentStorageException("存储请求参数不合法");
        }

        String storageKey = UUID.randomUUID() + "." + extension;
        Path target = resolveStorageKey(storageKey);
        Path temporary = null;
        try {
            Files.createDirectories(storageRoot);
            temporary = Files.createTempFile(storageRoot, "upload-", ".tmp");
            long copiedSize = Files.copy(input, temporary, StandardCopyOption.REPLACE_EXISTING);
            if (copiedSize != size) {
                throw new DocumentStorageException("上传流大小与元数据不一致");
            }
            moveAtomicallyWhenSupported(temporary, target);
            return new StoredObject(storageKey, copiedSize);
        }
        catch (IOException exception) {
            throw new DocumentStorageException("保存文档失败", exception);
        }
        finally {
            if (temporary != null) {
                try {
                    Files.deleteIfExists(temporary);
                }
                catch (IOException ignored) {
                    // 临时文件位于配置根目录内，可由后续清理任务处理。
                }
            }
        }
    }

    @Override
    public InputStream load(String storageKey) {
        try {
            return Files.newInputStream(resolveStorageKey(storageKey));
        }
        catch (IOException exception) {
            throw new DocumentStorageException("读取文档失败", exception);
        }
    }

    @Override
    public void delete(String storageKey) {
        try {
            Files.deleteIfExists(resolveStorageKey(storageKey));
        }
        catch (IOException exception) {
            throw new DocumentStorageException("删除文档失败", exception);
        }
    }

    private void moveAtomicallyWhenSupported(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
        }
        catch (AtomicMoveNotSupportedException exception) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private Path resolveStorageKey(String storageKey) {
        if (storageKey == null || !STORAGE_KEY_PATTERN.matcher(storageKey).matches()) {
            throw new DocumentStorageException("存储键不合法");
        }
        Path resolved = storageRoot.resolve(storageKey).normalize();
        if (!resolved.startsWith(storageRoot)) {
            throw new DocumentStorageException("存储键越出了配置根目录");
        }
        return resolved;
    }
}
