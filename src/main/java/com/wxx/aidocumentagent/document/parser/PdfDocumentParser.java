package com.wxx.aidocumentagent.document.parser;

import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.wxx.aidocumentagent.common.api.BusinessException;
import com.wxx.aidocumentagent.document.domain.DocumentErrorCode;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.io.RandomAccessReadBuffer;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDDocumentInformation;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.graphics.form.PDFormXObject;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * 使用 PDFBox 逐页提取文本，保留完整文本字符区间到原页码的映射。
 */
@Component
public final class PdfDocumentParser implements DocumentParser {

    private final PdfOcrProperties ocrProperties;
    private final PdfPageOcr pageOcr;

    public PdfDocumentParser() {
        this(new PdfOcrProperties(), null);
    }

    @Autowired
    public PdfDocumentParser(PdfOcrProperties ocrProperties, PdfPageOcr pageOcr) {
        this.ocrProperties = java.util.Objects.requireNonNull(ocrProperties);
        this.pageOcr = pageOcr;
    }

    @Override
    public boolean supports(DocumentType type) {
        return type == DocumentType.PDF;
    }

    @Override
    public ParsedDocument parse(DocumentSource source) {
        requireSupported(source);
        try (InputStream input = source.openStream();
             RandomAccessReadBuffer randomAccessRead = new RandomAccessReadBuffer(input);
             PDDocument document = Loader.loadPDF(randomAccessRead)) {
            if (document.isEncrypted()) {
                throw new BusinessException(DocumentErrorCode.PARSE_ENCRYPTED_PDF);
            }
            return extract(document);
        }
        catch (BusinessException exception) {
            throw exception;
        }
        catch (InvalidPasswordException exception) {
            throw parseError(DocumentErrorCode.PARSE_ENCRYPTED_PDF, exception);
        }
        catch (IOException | RuntimeException exception) {
            throw parseError(DocumentErrorCode.PARSE_CORRUPTED_DOCUMENT, exception);
        }
    }

    private ParsedDocument extract(PDDocument document) throws IOException {
        PDFTextStripper textStripper = new PDFTextStripper();
        textStripper.setSortByPosition(true);

        StringBuilder completeText = new StringBuilder();
        List<ParsedSection> sections = new ArrayList<>();
        List<ParsedPageMapping> pageMappings = new ArrayList<>();
        List<ParseWarning> warnings = new ArrayList<>();
        int pageCount = document.getNumberOfPages();
        int ocrPages = 0;
        long deadline = System.nanoTime() + ocrProperties.getDocumentTimeout().toNanos();

        for (int pageNumber = 1; pageNumber <= pageCount; pageNumber++) {
            if (ocrProperties.isEnabled()
                    && (Thread.currentThread().isInterrupted() || System.nanoTime() >= deadline)) {
                throw new BusinessException(DocumentErrorCode.OCR_TIMEOUT);
            }
            textStripper.setStartPage(pageNumber);
            textStripper.setEndPage(pageNumber);
            String pageText = TextNormalizer.normalize(textStripper.getText(document));
            boolean unreadableSource = ExtractedTextQuality.isUnreadable(pageText);
            boolean recognized = false;
            if (ocrProperties.isEnabled() && needsOcr(document.getPage(pageNumber - 1), pageText)) {
                if (++ocrPages > ocrProperties.getMaxPages()) {
                    throw new BusinessException(DocumentErrorCode.OCR_LIMIT_EXCEEDED);
                }
                if (pageOcr == null) throw new BusinessException(DocumentErrorCode.OCR_UNAVAILABLE);
                pageText = TextNormalizer.normalize(pageOcr.recognize(document, pageNumber - 1,
                        Duration.ofNanos(Math.max(0, deadline - System.nanoTime()))))
                        .replaceAll("(?<=\\p{IsHan})[ \\t]+(?=\\p{IsHan})", "");
                if (System.nanoTime() >= deadline) throw new BusinessException(DocumentErrorCode.OCR_TIMEOUT);
                if (unreadableSource && pageText.isBlank()) {
                    throw new BusinessException(DocumentErrorCode.OCR_FAILED);
                }
                recognized = true;
                warnings.add(new ParseWarning(ParseWarningCode.OCR_APPLIED,
                        "第" + pageNumber + "页已通过OCR提取文字，请留意识别误差", pageNumber));
            }
            if (ExtractedTextQuality.isUnreadable(pageText)) {
                throw new BusinessException(DocumentErrorCode.PARSE_UNREADABLE_TEXT);
            }
            if (pageText.isBlank()) {
                warnings.add(new ParseWarning(ParseWarningCode.EMPTY_PAGE,
                        "第" + pageNumber + "页不包含可解析文本", pageNumber));
            }
            if (ExtractedTextQuality.containsUnusableCharacters(pageText)) {
                warnings.add(new ParseWarning(ParseWarningCode.SUSPECTED_GARBLED_TEXT,
                        "第" + pageNumber + "页包含异常控制字符或替换字符，可能存在乱码", pageNumber));
            }

            if (!pageText.isBlank() && completeText.length() > 0) {
                completeText.append("\n\n");
            }
            int startOffset = completeText.length();
            if (!pageText.isBlank()) {
                completeText.append(pageText);
            }
            int endOffset = completeText.length();

            sections.add(new ParsedSection(pageNumber, ParsedSection.Kind.PAGE, pageNumber,
                    startOffset, endOffset, Map.of("extractionMethod", recognized ? "OCR" : "TEXT")));
            pageMappings.add(new ParsedPageMapping(pageNumber, startOffset, endOffset));
        }

        String text = completeText.toString();
        if (text.isBlank()) {
            throw new BusinessException(DocumentErrorCode.PARSE_EMPTY_TEXT);
        }
        if (ExtractedTextQuality.isUnreadable(text)) {
            throw new BusinessException(DocumentErrorCode.PARSE_UNREADABLE_TEXT);
        }
        Map<String, String> metadata = extractMetadata(document.getDocumentInformation(), pageCount);
        metadata.put("ocrPageCount", Integer.toString(ocrPages));
        return new ParsedDocument(text, sections, metadata, pageMappings, warnings);
    }

    private boolean needsOcr(PDPage page, String text) throws IOException {
        if (ExtractedTextQuality.isUnreadable(text)) return true;
        if (text.isBlank()) return page.hasContents();
        // Scanned pages often still have a selectable page number or short header.
        return text.codePoints().filter(codePoint -> !Character.isWhitespace(codePoint)).limit(80).count() < 80
                && containsImage(page.getResources(), 0);
    }

    private boolean containsImage(PDResources resources, int depth) throws IOException {
        if (resources == null || depth > 8) return false;
        for (var name : resources.getXObjectNames()) {
            var object = resources.getXObject(name);
            if (object instanceof PDImageXObject) return true;
            if (object instanceof PDFormXObject form && containsImage(form.getResources(), depth + 1)) return true;
        }
        return false;
    }

    private Map<String, String> extractMetadata(PDDocumentInformation information, int pageCount) {
        Map<String, String> metadata = new LinkedHashMap<>();
        metadata.put("pageCount", Integer.toString(pageCount));
        if (information != null) {
            addMetadata(metadata, "title", information.getTitle());
            addMetadata(metadata, "author", information.getAuthor());
            addMetadata(metadata, "subject", information.getSubject());
            addMetadata(metadata, "keywords", information.getKeywords());
        }
        return metadata;
    }

    private void addMetadata(Map<String, String> metadata, String key, String value) {
        String normalized = TextNormalizer.normalize(value);
        if (!normalized.isBlank()) {
            metadata.put(key, normalized);
        }
    }

    private void requireSupported(DocumentSource source) {
        if (source == null || !supports(source.documentType())) {
            throw new BusinessException(DocumentErrorCode.PARSE_UNSUPPORTED_TYPE);
        }
    }

    private BusinessException parseError(DocumentErrorCode errorCode, Exception cause) {
        return new BusinessException(errorCode, errorCode.defaultMessage(), cause);
    }
}
