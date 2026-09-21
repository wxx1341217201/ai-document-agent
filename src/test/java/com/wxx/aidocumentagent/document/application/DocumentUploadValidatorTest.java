package com.wxx.aidocumentagent.document.application;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DocumentUploadValidatorTest {

    private DocumentUploadValidator validator;

    @BeforeEach
    void setUp() {
        validator = new DocumentUploadValidator(new com.wxx.aidocumentagent.document.storage.DocumentStorageProperties());
    }

    @Test
    void 接受声明MIME类型正确的UTF8文本文件() {
        byte[] content = "知识库说明".getBytes(StandardCharsets.UTF_8);

        DocumentUploadValidator.ValidatedDocument validated = validator.validate(
                command("guide.txt", "text/plain; charset=UTF-8", content));

        assertThat(validated.extension()).isEqualTo("txt");
        assertThat(validated.contentType()).isEqualTo("text/plain");
    }

    @Test
    void 仅在包含必需OpenXML条目时接受DOCX文件() throws Exception {
        byte[] content = validDocx();

        DocumentUploadValidator.ValidatedDocument validated = validator.validate(
                command("guide.docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document", content));

        assertThat(validated.extension()).isEqualTo("docx");
    }

    private UploadDocumentCommand command(String name, String contentType, byte[] content) {
        return new UploadDocumentCommand(name, contentType, content.length, () -> new ByteArrayInputStream(content));
    }

    private byte[] validDocx() throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(output)) {
            zip.putNextEntry(new ZipEntry("[Content_Types].xml"));
            zip.write("<Types/>".getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
            zip.putNextEntry(new ZipEntry("word/document.xml"));
            zip.write("<w:document/>".getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        return output.toByteArray();
    }
}
