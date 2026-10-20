package com.wxx.aidocumentagent.document.parser;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.awt.image.BufferedImage;
import java.util.List;
import java.util.ArrayList;

import com.wxx.aidocumentagent.common.api.BusinessException;
import com.wxx.aidocumentagent.document.domain.DocumentErrorCode;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDStream;
import org.apache.pdfbox.pdmodel.encryption.AccessPermission;
import org.apache.pdfbox.pdmodel.encryption.StandardProtectionPolicy;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
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

    @Test
    void brokenUnicodeMappingsCannotBeAcceptedAsParsedText() {
        for (String unicode : List.of("0002", "FFFD")) {
            assertThatThrownBy(() -> parser.parse(source("broken-text-layer.pdf", unreadablePdf(unicode))))
                    .isInstanceOfSatisfying(BusinessException.class, exception ->
                            assertThat(exception.getErrorCode()).isEqualTo(DocumentErrorCode.PARSE_UNREADABLE_TEXT));
        }
    }

    @Test
    void brokenTextLayerFallsBackToOcrAndPreservesOriginalPageMapping() {
        var properties = new PdfOcrProperties();
        properties.setEnabled(true);
        var observed = new ArrayList<Integer>();
        var withOcr = new PdfDocumentParser(properties, (document, page, timeout) -> {
            observed.add(page);
            assertThat(timeout).isPositive();
            return "贾 宝 玉 是贾政与王夫人的儿子。\n";
        });

        var parsed = withOcr.parse(source("broken.pdf", unreadablePdf("0002")));
        assertThat(parsed.text()).isEqualTo("贾宝玉是贾政与王夫人的儿子。");
        assertThat(observed).containsExactly(0);
        assertThat(parsed.metadata()).containsEntry("ocrPageCount", "1");
        assertThat(parsed.sections().getFirst().attributes()).containsEntry("extractionMethod", "OCR");
        assertThat(parsed.warnings()).extracting(ParseWarning::code).contains(ParseWarningCode.OCR_APPLIED);
        assertPageMappingsTraceBackToSourceSections(parsed);
    }

    @Test
    void readablePdfAndTrulyBlankPagesDoNotInvokeOcr() {
        var properties = new PdfOcrProperties();
        properties.setEnabled(true);
        var withOcr = new PdfDocumentParser(properties, (document, page, timeout) -> {
            throw new AssertionError("readable text and blank pages must not invoke OCR");
        });
        var parsed = withOcr.parse(fixture());
        assertThat(parsed.text()).isEqualTo("Page one text\n\nPage three text");
        assertThat(parsed.metadata()).containsEntry("ocrPageCount", "0");
        assertPageMappingsTraceBackToSourceSections(parsed);
    }

    @Test
    void scannedPageWithSelectableFooterStillInvokesOcr() {
        var properties = new PdfOcrProperties();
        properties.setEnabled(true);
        var withOcr = new PdfDocumentParser(properties, (document, page, timeout) -> "扫描正文。\n42");
        var parsed = withOcr.parse(source("scanned.pdf", scannedPdfWithFooter()));
        assertThat(parsed.text()).contains("扫描正文");
        assertThat(parsed.metadata()).containsEntry("ocrPageCount", "1");
        assertPageMappingsTraceBackToSourceSections(parsed);
    }

    @Test
    void failedOrStillGarbledOcrCannotSilentlyBecomeUsableText() {
        var properties = new PdfOcrProperties();
        properties.setEnabled(true);
        var broken = source("broken.pdf", unreadablePdf("0002"));
        assertThatThrownBy(() -> new PdfDocumentParser(properties, (document, page, timeout) -> "\u0002".repeat(20)).parse(broken))
                .isInstanceOfSatisfying(BusinessException.class, failure ->
                        assertThat(failure.getErrorCode()).isEqualTo(DocumentErrorCode.PARSE_UNREADABLE_TEXT));
        assertThatThrownBy(() -> new PdfDocumentParser(properties, (document, page, timeout) -> {
            throw new BusinessException(DocumentErrorCode.OCR_TIMEOUT);
        }).parse(broken)).isInstanceOfSatisfying(BusinessException.class, failure ->
                assertThat(failure.getErrorCode()).isEqualTo(DocumentErrorCode.OCR_TIMEOUT));
        assertThatThrownBy(() -> new PdfDocumentParser(properties, (document, page, timeout) -> "").parse(broken))
                .isInstanceOfSatisfying(BusinessException.class, failure ->
                        assertThat(failure.getErrorCode()).isEqualTo(DocumentErrorCode.OCR_FAILED));
    }

    @Test
    void ocrPageLimitDoesNotReturnPartiallyRecognizedDocument() {
        var properties = new PdfOcrProperties();
        properties.setEnabled(true);
        properties.setMaxPages(0);
        var withOcr = new PdfDocumentParser(properties, (document, page, timeout) -> {
            throw new AssertionError("page limit must be checked before OCR");
        });
        assertThatThrownBy(() -> withOcr.parse(source("scanned.pdf", scannedPdfWithFooter())))
                .isInstanceOfSatisfying(BusinessException.class, failure ->
                        assertThat(failure.getErrorCode()).isEqualTo(DocumentErrorCode.OCR_LIMIT_EXCEEDED));
    }

    @Test
    void documentDeadlineStopsBeforeCallingOcr() {
        var properties = new PdfOcrProperties();
        properties.setEnabled(true);
        properties.setDocumentTimeout(java.time.Duration.ofNanos(1));
        var withOcr = new PdfDocumentParser(properties, (document, page, timeout) -> {
            throw new AssertionError("document deadline must be checked before OCR");
        });
        assertThatThrownBy(() -> withOcr.parse(source("scanned.pdf", scannedPdfWithFooter())))
                .isInstanceOfSatisfying(BusinessException.class, failure ->
                        assertThat(failure.getErrorCode()).isEqualTo(DocumentErrorCode.OCR_TIMEOUT));
    }

    private byte[] scannedPdfWithFooter() {
        try (var document = new PDDocument(); var output = new ByteArrayOutputStream()) {
            var page = new PDPage();
            document.addPage(page);
            var image = new BufferedImage(40, 40, BufferedImage.TYPE_INT_RGB);
            try (var content = new PDPageContentStream(document, page)) {
                content.drawImage(LosslessFactory.createFromImage(document, image), 50, 100, 400, 500);
                content.beginText();
                content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                content.newLineAtOffset(50, 50);
                content.showText("42");
                content.endText();
            }
            document.save(output);
            return output.toByteArray();
        }
        catch (IOException failure) { throw new AssertionError(failure); }
    }

    private byte[] unreadablePdf(String unicode) {
        try (PDDocument document = new PDDocument(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            PDPage page = new PDPage();
            document.addPage(page);
            var font = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
            var mapping = new PDStream(document);
            try (var stream = mapping.createOutputStream()) {
                stream.write(("""
                        /CIDInit /ProcSet findresource begin
                        12 dict begin
                        begincmap
                        /CIDSystemInfo << /Registry (Adobe) /Ordering (UCS) /Supplement 0 >> def
                        /CMapName /BrokenTextLayer def
                        /CMapType 2 def
                        1 begincodespacerange
                        <00> <FF>
                        endcodespacerange
                        1 beginbfchar
                        <41> <%s>
                        endbfchar
                        endcmap
                        CMapName currentdict /CMap defineresource pop
                        end
                        end
                        """).formatted(unicode).getBytes(StandardCharsets.US_ASCII));
            }
            font.getCOSObject().setItem(COSName.TO_UNICODE, mapping);
            try (var content = new PDPageContentStream(document, page)) {
                content.beginText();
                content.setFont(font, 12);
                content.newLineAtOffset(50, 700);
                content.showText("AAAAAAAAAAAAAAAAAAAAAAAA");
                content.endText();
            }
            document.save(output);
            return output.toByteArray();
        }
        catch (IOException exception) {
            throw new AssertionError(exception);
        }
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
