package com.wxx.aidocumentagent.document.parser;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.wxx.aidocumentagent.common.api.BusinessException;
import com.wxx.aidocumentagent.document.domain.DocumentErrorCode;
import org.apache.poi.ooxml.POIXMLProperties;
import org.apache.poi.xwpf.usermodel.IBodyElement;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.apache.poi.xwpf.usermodel.XWPFTableCell;
import org.apache.poi.xwpf.usermodel.XWPFTableRow;
import org.springframework.stereotype.Component;

/**
 * 提取 DOCX 正文中的段落、标题和表格，并保持正文元素的原始顺序。
 */
@Component
public final class DocxDocumentParser implements DocumentParser {

    private static final Pattern HEADING_STYLE = Pattern.compile("heading\\s*([1-9])", Pattern.CASE_INSENSITIVE);

    @Override
    public boolean supports(DocumentType type) {
        return type == DocumentType.DOCX;
    }

    @Override
    public ParsedDocument parse(DocumentSource source) {
        requireSupported(source);
        try (InputStream input = source.openStream();
             XWPFDocument document = new XWPFDocument(input)) {
            return extract(document);
        }
        catch (BusinessException exception) {
            throw exception;
        }
        catch (IOException | RuntimeException exception) {
            throw parseError(DocumentErrorCode.PARSE_CORRUPTED_DOCUMENT, exception);
        }
    }

    private ParsedDocument extract(XWPFDocument document) {
        Map<String, String> metadata = extractMetadata(document);
        StringBuilder completeText = new StringBuilder();
        List<ParsedSection> sections = new ArrayList<>();
        List<ParseWarning> warnings = new ArrayList<>();
        int tableIndex = 0;

        for (IBodyElement bodyElement : document.getBodyElements()) {
            if (bodyElement instanceof XWPFParagraph paragraph) {
                addParagraph(paragraph, metadata, completeText, sections, warnings);
            }
            else if (bodyElement instanceof XWPFTable table) {
                tableIndex++;
                addTable(table, tableIndex, completeText, sections, warnings);
            }
        }

        String text = completeText.toString();
        if (text.isBlank()) {
            throw new BusinessException(DocumentErrorCode.PARSE_EMPTY_TEXT);
        }
        return new ParsedDocument(text, sections, metadata, List.of(), warnings);
    }

    private Map<String, String> extractMetadata(XWPFDocument document) {
        Map<String, String> metadata = new LinkedHashMap<>();
        POIXMLProperties.CoreProperties coreProperties = document.getProperties().getCoreProperties();
        addMetadata(metadata, "title", coreProperties.getTitle());
        addMetadata(metadata, "author", coreProperties.getCreator());
        return metadata;
    }

    private void addParagraph(XWPFParagraph paragraph, Map<String, String> metadata, StringBuilder completeText,
                              List<ParsedSection> sections, List<ParseWarning> warnings) {
        String text = TextNormalizer.normalize(paragraph.getText());
        if (text.isBlank()) {
            return;
        }

        Map<String, String> attributes = new LinkedHashMap<>();
        String style = paragraph.getStyle();
        if (style != null && !style.isBlank()) {
            attributes.put("style", style);
            headingLevel(style).ifPresent(level -> attributes.put("headingLevel", Integer.toString(level)));
            if (isTitleStyle(style) && !metadata.containsKey("title")) {
                metadata.put("title", text);
            }
        }

        TextRange range = appendSection(completeText, text);
        sections.add(new ParsedSection(sections.size() + 1, ParsedSection.Kind.PARAGRAPH, null,
                range.startOffset(), range.endOffset(), attributes));
        addGarbledWarning(text, warnings, null);
    }

    private void addTable(XWPFTable table, int tableIndex, StringBuilder completeText, List<ParsedSection> sections,
                          List<ParseWarning> warnings) {
        List<String> rows = new ArrayList<>();
        int columnCount = 0;
        for (XWPFTableRow row : table.getRows()) {
            List<XWPFTableCell> cells = row.getTableCells();
            columnCount = Math.max(columnCount, cells.size());
            List<String> cellTexts = new ArrayList<>(cells.size());
            for (XWPFTableCell cell : cells) {
                cellTexts.add(extractCellText(cell));
            }
            String rowText = TextNormalizer.normalize(String.join(" | ", cellTexts));
            if (!rowText.isBlank()) {
                rows.add(rowText);
            }
        }

        String text = TextNormalizer.normalize(String.join("\n", rows));
        if (text.isBlank()) {
            return;
        }
        Map<String, String> attributes = Map.of(
                "tableIndex", Integer.toString(tableIndex),
                "rowCount", Integer.toString(rows.size()),
                "columnCount", Integer.toString(columnCount));
        TextRange range = appendSection(completeText, text);
        sections.add(new ParsedSection(sections.size() + 1, ParsedSection.Kind.TABLE, null,
                range.startOffset(), range.endOffset(), attributes));
        addGarbledWarning(text, warnings, null);
    }

    private String extractCellText(XWPFTableCell cell) {
        List<String> paragraphs = new ArrayList<>();
        for (XWPFParagraph paragraph : cell.getParagraphs()) {
            String text = TextNormalizer.normalize(paragraph.getText());
            if (!text.isBlank()) {
                paragraphs.add(text);
            }
        }
        return TextNormalizer.normalize(String.join("\n", paragraphs));
    }

    private TextRange appendSection(StringBuilder completeText, String sectionText) {
        if (completeText.length() > 0) {
            completeText.append("\n\n");
        }
        int startOffset = completeText.length();
        completeText.append(sectionText);
        return new TextRange(startOffset, completeText.length());
    }

    private void addGarbledWarning(String text, List<ParseWarning> warnings, Integer pageNumber) {
        if (TextNormalizer.containsReplacementCharacter(text)) {
            warnings.add(new ParseWarning(ParseWarningCode.SUSPECTED_GARBLED_TEXT,
                    "文本包含替换字符，可能存在乱码", pageNumber));
        }
    }

    private java.util.OptionalInt headingLevel(String style) {
        Matcher matcher = HEADING_STYLE.matcher(style);
        return matcher.find() ? java.util.OptionalInt.of(Integer.parseInt(matcher.group(1)))
                : java.util.OptionalInt.empty();
    }

    private boolean isTitleStyle(String style) {
        return style.toLowerCase(Locale.ROOT).contains("title");
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

    private record TextRange(int startOffset, int endOffset) {
    }
}
