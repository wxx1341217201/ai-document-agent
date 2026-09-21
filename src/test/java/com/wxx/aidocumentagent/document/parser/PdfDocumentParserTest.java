package com.wxx.aidocumentagent.document.parser;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;

import com.wxx.aidocumentagent.common.api.BusinessException;
import com.wxx.aidocumentagent.document.domain.DocumentErrorCode;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.encryption.AccessPermission;
import org.apache.pdfbox.pdmodel.encryption.StandardProtectionPolicy;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PdfDocumentParserTest {

    private final PdfDocumentParser parser = new PdfDocumentParser();

    @Test
    void 解析Pdf夹具并将片段映射回页码() {
        ParsedDocument parsed = parser.parse(fixture());

        assertThat(parsed.text()).isEqualTo("Page one text\n\nPage three text");
        assertThat(parsed.metadata())
                .containsEntry("title", "Sample PDF")
                .containsEntry("author", "Parser Tester")
                .containsEntry("pageCount", "3");
        assertThat(parsed.sections()).extracting(ParsedSection::pageNumber).containsExactly(1, 2, 3);
        assertThat(parsed.warnings()).extracting(ParseWarning::code).contains(ParseWarningCode.EMPTY_PAGE);
        assertPageMappingsTraceBackToSourceSections(parsed);
    }

    @Test
    void 加密Pdf返回稳定错误码() {
        assertThatThrownBy(() -> parser.parse(source("encrypted.pdf", encryptedPdf())))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(DocumentErrorCode.PARSE_ENCRYPTED_PDF));
    }

    @Test
    void 损坏Pdf返回稳定错误码() {
        assertThatThrownBy(() -> parser.parse(source("corrupted.pdf", "not-a-pdf".getBytes())))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(DocumentErrorCode.PARSE_CORRUPTED_DOCUMENT));
    }

    @Test
    void 全为空白页的Pdf返回稳定错误码() {
        assertThatThrownBy(() -> parser.parse(source("blank.pdf", blankPdf())))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(DocumentErrorCode.PARSE_EMPTY_TEXT));
    }

    private void assertPageMappingsTraceBackToSourceSections(ParsedDocument parsed) {
        List<ParsedSection> sections = parsed.sections();
        List<ParsedPageMapping> pageMappings = parsed.pageMappings();
        assertThat(pageMappings).hasSameSizeAs(sections);
        for (int index = 0; index < pageMappings.size(); index++) {
            ParsedPageMapping pageMapping = pageMappings.get(index);
            ParsedSection section = sections.get(index);
            assertThat(pageMapping.pageNumber()).isEqualTo(section.pageNumber());
            assertThat(parsed.text().substring(pageMapping.startOffset(), pageMapping.endOffset()))
                    .isEqualTo(parsed.textOf(section));
        }
    }

    private DocumentSource fixture() {
        return new DocumentSource(DocumentType.PDF, "sample.pdf",
                new ClassPathResource("document-parser/sample.pdf"));
    }

    private DocumentSource source(String name, byte[] content) {
        return new DocumentSource(DocumentType.PDF, name, () -> new ByteArrayInputStream(content));
    }

    private byte[] encryptedPdf() {
        try (PDDocument document = new PDDocument();
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            document.addPage(new PDPage());
            document.protect(new StandardProtectionPolicy("owner-password", "user-password", new AccessPermission()));
            document.save(output);
            return output.toByteArray();
        }
        catch (IOException exception) {
            throw new AssertionError(exception);
        }
    }

    private byte[] blankPdf() {
        try (PDDocument document = new PDDocument();
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            document.addPage(new PDPage());
            document.save(output);
            return output.toByteArray();
        }
        catch (IOException exception) {
            throw new AssertionError(exception);
        }
    }
}
