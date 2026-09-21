package com.wxx.aidocumentagent.chunking;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.wxx.aidocumentagent.document.parser.ParsedDocument;
import com.wxx.aidocumentagent.document.parser.ParsedSection;
import org.springframework.stereotype.Component;

/**
 * 优先合并完整段落；单个段落超过窗口时才退化为固定窗口切分。
 */
@Component
public final class ParagraphChunkingStrategy implements ChunkingStrategy {

    public static final String STRATEGY_NAME = "paragraph";

    private static final Pattern PARAGRAPH_SEPARATOR = Pattern.compile("(?:\\r?\\n[\\t \\f]*){2,}");

    @Override
    public String name() {
        return STRATEGY_NAME;
    }

    @Override
    public List<TextChunk> split(ParsedDocument document, ChunkingOptions options) {
        Objects.requireNonNull(document, "document不能为空");
        Objects.requireNonNull(options, "options不能为空");

        List<Paragraph> paragraphs = paragraphs(document);
        if (paragraphs.isEmpty()) {
            return List.of();
        }

        List<ChunkingSupport.SourceSlice> slices = new ArrayList<>();
        int currentStart = -1;
        int currentEnd = -1;
        for (Paragraph paragraph : paragraphs) {
            int paragraphSize = ChunkingSupport.measure(document.text(), paragraph.startOffset(), paragraph.endOffset(),
                    options.windowUnit());
            if (paragraphSize > options.chunkSize()) {
                appendCurrent(slices, currentStart, currentEnd);
                slices.addAll(ChunkingSupport.fixedWindowSlices(document.text(), paragraph.startOffset(),
                        paragraph.endOffset(), options));
                currentStart = -1;
                currentEnd = -1;
            }
            else if (currentStart < 0) {
                currentStart = paragraph.startOffset();
                currentEnd = paragraph.endOffset();
            }
            else if (ChunkingSupport.measure(document.text(), currentStart, paragraph.endOffset(),
                    options.windowUnit()) <= options.chunkSize()) {
                currentEnd = paragraph.endOffset();
            }
            else {
                slices.add(new ChunkingSupport.SourceSlice(currentStart, currentEnd));
                currentStart = overlapStart(document.text(), currentStart, currentEnd, paragraph, paragraphs, options);
                if (ChunkingSupport.measure(document.text(), currentStart, paragraph.endOffset(),
                        options.windowUnit()) > options.chunkSize()) {
                    currentStart = paragraph.startOffset();
                }
                currentEnd = paragraph.endOffset();
            }
        }
        appendCurrent(slices, currentStart, currentEnd);
        return ChunkingSupport.toTextChunks(document, slices, name());
    }

    private List<Paragraph> paragraphs(ParsedDocument document) {
        List<Paragraph> sectionParagraphs = document.sections().stream()
                .filter(section -> section.kind() == ParsedSection.Kind.PARAGRAPH
                        || section.kind() == ParsedSection.Kind.TABLE)
                .sorted(Comparator.comparingInt(ParsedSection::startOffset)
                        .thenComparingInt(ParsedSection::sequence))
                .map(section -> ChunkingSupport.trim(document.text(), section.startOffset(), section.endOffset()))
                .filter(Objects::nonNull)
                .map(slice -> new Paragraph(slice.startOffset(), slice.endOffset()))
                .toList();
        if (!sectionParagraphs.isEmpty() && coversAllNonBlankText(document.text(), sectionParagraphs)) {
            return sectionParagraphs;
        }
        return paragraphsFromBlankLines(document.text());
    }

    private boolean coversAllNonBlankText(String text, List<Paragraph> paragraphs) {
        int coveredEnd = 0;
        for (Paragraph paragraph : paragraphs) {
            if (paragraph.startOffset() < coveredEnd
                    || !text.substring(coveredEnd, paragraph.startOffset()).isBlank()) {
                return false;
            }
            coveredEnd = paragraph.endOffset();
        }
        return text.substring(coveredEnd).isBlank();
    }

    private List<Paragraph> paragraphsFromBlankLines(String text) {
        List<Paragraph> paragraphs = new ArrayList<>();
        Matcher matcher = PARAGRAPH_SEPARATOR.matcher(text);
        int startOffset = 0;
        while (matcher.find()) {
            addParagraph(text, startOffset, matcher.start(), paragraphs);
            startOffset = matcher.end();
        }
        addParagraph(text, startOffset, text.length(), paragraphs);
        return List.copyOf(paragraphs);
    }

    private void addParagraph(String text, int startOffset, int endOffset, List<Paragraph> paragraphs) {
        ChunkingSupport.SourceSlice slice = ChunkingSupport.trim(text, startOffset, endOffset);
        if (slice != null) {
            paragraphs.add(new Paragraph(slice.startOffset(), slice.endOffset()));
        }
    }

    private int overlapStart(String text, int currentStart, int currentEnd, Paragraph nextParagraph,
                             List<Paragraph> paragraphs, ChunkingOptions options) {
        int nextParagraphSize = ChunkingSupport.measure(text, nextParagraph.startOffset(), nextParagraph.endOffset(),
                options.windowUnit());
        int maxOverlap = Math.min(options.overlap(), options.chunkSize() - nextParagraphSize);
        if (maxOverlap <= 0) {
            return nextParagraph.startOffset();
        }

        Integer paragraphBoundary = null;
        for (Paragraph paragraph : paragraphs) {
            if (paragraph.startOffset() < currentStart || paragraph.endOffset() > currentEnd) {
                continue;
            }
            if (ChunkingSupport.measure(text, paragraph.startOffset(), currentEnd, options.windowUnit()) <= maxOverlap) {
                paragraphBoundary = paragraph.startOffset();
            }
        }
        if (paragraphBoundary != null) {
            return paragraphBoundary;
        }
        return ChunkingSupport.trailingStart(text, currentStart, currentEnd, maxOverlap, options.windowUnit());
    }

    private void appendCurrent(List<ChunkingSupport.SourceSlice> slices, int currentStart, int currentEnd) {
        if (currentStart >= 0) {
            slices.add(new ChunkingSupport.SourceSlice(currentStart, currentEnd));
        }
    }

    private record Paragraph(int startOffset, int endOffset) {
    }
}
