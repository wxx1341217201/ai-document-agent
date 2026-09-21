package com.wxx.aidocumentagent.document.parser;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

import com.wxx.aidocumentagent.common.api.BusinessException;
import com.wxx.aidocumentagent.document.domain.DocumentErrorCode;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TxtDocumentParserTest {

    private final TxtDocumentParser parser = new TxtDocumentParser();

    @Test
    void 解析带Utf8Bom的文本夹具并规范化空白() {
        ParsedDocument parsed = parser.parse(fixture("sample.txt"));

        assertThat(parsed.text()).isEqualTo("First paragraph\n\nSecond paragraph");
        assertThat(parsed.metadata()).containsEntry("encoding", "UTF-8");
        assertThat(parsed.sections().stream().map(parsed::textOf).toList())
                .containsExactly("First paragraph", "Second paragraph");
        assertThat(parsed.pageMappings()).isEmpty();
        assertThat(parsed.warnings()).isEmpty();
    }

    @Test
    void 识别Utf16LittleEndianBom() {
        byte[] content = "\uFEFFAlpha  text\r\n\r\nBeta".getBytes(StandardCharsets.UTF_16LE);

        ParsedDocument parsed = parser.parse(source("utf16.txt", content));

        assertThat(parsed.text()).isEqualTo("Alpha text\n\nBeta");
        assertThat(parsed.metadata()).containsEntry("encoding", "UTF-16LE");
    }

    @Test
    void 无效编码返回稳定错误码() {
        assertThatThrownBy(() -> parser.parse(source("invalid.txt", new byte[]{(byte) 0xC3, 0x28})))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(DocumentErrorCode.PARSE_INVALID_TEXT_ENCODING));
    }

    @Test
    void 二进制文本返回稳定错误码() {
        assertThatThrownBy(() -> parser.parse(source("binary.txt", "text\u0000payload".getBytes(StandardCharsets.UTF_8))))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(DocumentErrorCode.PARSE_BINARY_TEXT));
    }

    @Test
    void 空文本返回稳定错误码() {
        assertThatThrownBy(() -> parser.parse(source("empty.txt", " \r\n\t\r\n".getBytes(StandardCharsets.UTF_8))))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(DocumentErrorCode.PARSE_EMPTY_TEXT));
    }

    private DocumentSource fixture(String name) {
        return new DocumentSource(DocumentType.TXT, name, new ClassPathResource("document-parser/" + name));
    }

    private DocumentSource source(String name, byte[] content) {
        return new DocumentSource(DocumentType.TXT, name, () -> new ByteArrayInputStream(content));
    }
}
