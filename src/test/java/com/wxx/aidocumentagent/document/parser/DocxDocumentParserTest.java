package com.wxx.aidocumentagent.document.parser;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

import com.wxx.aidocumentagent.common.api.BusinessException;
import com.wxx.aidocumentagent.document.domain.DocumentErrorCode;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DocxDocumentParserTest {

    private final DocxDocumentParser parser = new DocxDocumentParser();

    @Test
    void 解析Docx夹具的段落标题和表格() {
        ParsedDocument parsed = parser.parse(fixture());

        assertThat(parsed.text()).isEqualTo("""
                Document title

                Section one

                First paragraph has extra spaces

                Name | Value
                alpha | 1

                Last paragraph""");
        assertThat(parsed.metadata())
                .containsEntry("title", "Document title")
                .containsEntry("author", "Parser Tester");
        assertThat(parsed.sections()).extracting(ParsedSection::kind).containsExactly(
                ParsedSection.Kind.PARAGRAPH,
                ParsedSection.Kind.PARAGRAPH,
                ParsedSection.Kind.PARAGRAPH,
                ParsedSection.Kind.TABLE,
                ParsedSection.Kind.PARAGRAPH);
        assertThat(parsed.sections().get(1).attributes()).containsEntry("headingLevel", "1");
        assertThat(parsed.sections().get(3).attributes())
                .containsEntry("tableIndex", "1")
                .containsEntry("rowCount", "2")
                .containsEntry("columnCount", "2");
        assertThat(parsed.pageMappings()).isEmpty();
    }

    @Test
    void 损坏Docx返回稳定错误码() {
        assertThatThrownBy(() -> parser.parse(source("corrupted.docx", "not-a-docx".getBytes())))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(DocumentErrorCode.PARSE_CORRUPTED_DOCUMENT));
    }

    @Test
    void 空Docx返回稳定错误码() {
        assertThatThrownBy(() -> parser.parse(source("empty.docx", emptyDocx())))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(DocumentErrorCode.PARSE_EMPTY_TEXT));
    }

    private DocumentSource fixture() {
        return new DocumentSource(DocumentType.DOCX, "sample.docx",
                new ClassPathResource("document-parser/sample.docx"));
    }

    private DocumentSource source(String name, byte[] content) {
        return new DocumentSource(DocumentType.DOCX, name, () -> new ByteArrayInputStream(content));
    }

    private byte[] emptyDocx() {
        try (XWPFDocument document = new XWPFDocument();
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            document.write(output);
            return output.toByteArray();
        }
        catch (IOException exception) {
            throw new AssertionError(exception);
        }
    }
}
