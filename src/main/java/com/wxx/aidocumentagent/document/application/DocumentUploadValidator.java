package com.wxx.aidocumentagent.document.application;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import com.wxx.aidocumentagent.common.api.BusinessException;
import com.wxx.aidocumentagent.common.api.CommonErrorCode;
import com.wxx.aidocumentagent.document.domain.DocumentErrorCode;
import com.wxx.aidocumentagent.document.storage.DocumentStorageProperties;
import org.springframework.core.io.InputStreamSource;
import org.springframework.stereotype.Component;

/**
 * 在保存文档原始字节前校验声明类型和基础文件签名。
 */
@Component
public class DocumentUploadValidator {

    private static final Map<String, AllowedFileType> ALLOWED_FILE_TYPES = Map.of(
            "pdf", new AllowedFileType("application/pdf"),
            "docx", new AllowedFileType("application/vnd.openxmlformats-officedocument.wordprocessingml.document"),
            "txt", new AllowedFileType("text/plain"));

    private final DocumentStorageProperties properties;

    public DocumentUploadValidator(DocumentStorageProperties properties) {
        this.properties = properties;
    }

    public ValidatedDocument validate(UploadDocumentCommand command) {
        String originalName = validateOriginalName(command.originalName());
        if (command.sizeBytes() <= 0) {
            throw new BusinessException(DocumentErrorCode.EMPTY_FILE);
        }
        if (command.sizeBytes() > properties.getMaxFileSize().toBytes()) {
            throw new BusinessException(CommonErrorCode.FILE_TOO_LARGE);
        }

        String extension = extractExtension(originalName);
        AllowedFileType allowedFileType = ALLOWED_FILE_TYPES.get(extension);
        if (allowedFileType == null || !allowedFileType.contentType().equals(normalizeContentType(command.contentType()))) {
            throw new BusinessException(DocumentErrorCode.UNSUPPORTED_FILE_TYPE);
        }

        if (!hasExpectedSignature(extension, command.content())) {
            throw new BusinessException(DocumentErrorCode.INVALID_FILE_CONTENT);
        }
        return new ValidatedDocument(originalName, extension, allowedFileType.contentType());
    }

    private String validateOriginalName(String originalName) {
        if (originalName == null || originalName.isBlank() || originalName.indexOf('/') >= 0 || originalName.indexOf('\\') >= 0
                || originalName.indexOf('\u0000') >= 0) {
            throw new BusinessException(DocumentErrorCode.INVALID_FILE_NAME);
        }
        return originalName.strip();
    }

    private String extractExtension(String originalName) {
        int separator = originalName.lastIndexOf('.');
        if (separator <= 0 || separator == originalName.length() - 1) {
            throw new BusinessException(DocumentErrorCode.UNSUPPORTED_FILE_TYPE);
        }
        return originalName.substring(separator + 1).toLowerCase(Locale.ROOT);
    }

    private String normalizeContentType(String contentType) {
        if (contentType == null) {
            return "";
        }
        int parameterStart = contentType.indexOf(';');
        return (parameterStart >= 0 ? contentType.substring(0, parameterStart) : contentType)
                .trim().toLowerCase(Locale.ROOT);
    }

    private boolean hasExpectedSignature(String extension, InputStreamSource source) {
        try (InputStream input = new BufferedInputStream(source.getInputStream())) {
            return switch (extension) {
                case "pdf" -> hasPdfSignature(input);
                case "docx" -> hasDocxSignature(input);
                case "txt" -> isUtf8Text(input);
                default -> false;
            };
        }
        catch (IOException | RuntimeException exception) {
            return false;
        }
    }

    private boolean hasPdfSignature(InputStream input) throws IOException {
        return input.read() == '%' && input.read() == 'P' && input.read() == 'D' && input.read() == 'F'
                && input.read() == '-';
    }

    private boolean hasDocxSignature(InputStream input) throws IOException {
        boolean hasContentTypes = false;
        boolean hasWordDocument = false;
        int entries = 0;
        try (ZipInputStream zip = new ZipInputStream(input)) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null && entries++ < 256) {
                String name = entry.getName();
                hasContentTypes |= "[Content_Types].xml".equals(name);
                hasWordDocument |= "word/document.xml".equals(name);
                if (hasContentTypes && hasWordDocument) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean isUtf8Text(InputStream input) throws IOException {
        try (Reader reader = new InputStreamReader(input,
                StandardCharsets.UTF_8.newDecoder()
                        .onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT))) {
            char[] buffer = new char[4_096];
            int read;
            while ((read = reader.read(buffer)) != -1) {
                for (int index = 0; index < read; index++) {
                    if (buffer[index] == '\u0000') {
                        return false;
                    }
                }
            }
            return true;
        }
    }

    public record ValidatedDocument(String originalName, String extension, String contentType) {
    }

    private record AllowedFileType(String contentType) {
    }
}
