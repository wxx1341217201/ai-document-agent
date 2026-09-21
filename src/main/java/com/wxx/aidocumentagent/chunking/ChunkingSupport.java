package com.wxx.aidocumentagent.chunking;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

import com.wxx.aidocumentagent.document.parser.ParsedDocument;
import com.wxx.aidocumentagent.document.parser.ParsedPageMapping;
import com.wxx.aidocumentagent.document.parser.ParsedSection;

final class ChunkingSupport {

    private static final int SECTION_TITLE_MAX_LENGTH = 512;

    private ChunkingSupport() {
    }

    static List<SourceSlice> fixedWindowSlices(String text, int sourceStart, int sourceEnd,
                                                ChunkingOptions options) {
        requireValidRange(text, sourceStart, sourceEnd);
        String source = text.substring(sourceStart, sourceEnd);
        List<TextUnitRanges.OffsetRange> units = TextUnitRanges.forUnit(source, options.windowUnit());
        if (units.isEmpty()) {
            return List.of();
        }

        List<SourceSlice> slices = new ArrayList<>();
        int unitStart = 0;
        while (unitStart < units.size()) {
            int unitEnd = Math.min(units.size(), unitStart + options.chunkSize());
            slices.add(new SourceSlice(sourceStart + units.get(unitStart).startOffset(),
                    sourceStart + units.get(unitEnd - 1).endOffset()));
            if (unitEnd == units.size()) {
                break;
            }
            unitStart = unitEnd - options.overlap();
        }
        return List.copyOf(slices);
    }

    static int measure(String text, int sourceStart, int sourceEnd, ChunkingUnit unit) {
        requireValidRange(text, sourceStart, sourceEnd);
        return TextUnitRanges.count(text.substring(sourceStart, sourceEnd), unit);
    }

    static int trailingStart(String text, int sourceStart, int sourceEnd, int unitsToKeep, ChunkingUnit unit) {
        requireValidRange(text, sourceStart, sourceEnd);
        if (unitsToKeep <= 0) {
            return sourceEnd;
        }
        String source = text.substring(sourceStart, sourceEnd);
        List<TextUnitRanges.OffsetRange> units = TextUnitRanges.forUnit(source, unit);
        if (units.isEmpty()) {
            return sourceEnd;
        }
        int unitStart = Math.max(0, units.size() - unitsToKeep);
        return sourceStart + units.get(unitStart).startOffset();
    }

    static SourceSlice trim(String text, int sourceStart, int sourceEnd) {
        requireValidRange(text, sourceStart, sourceEnd);
        int start = sourceStart;
        while (start < sourceEnd) {
            int codePoint = text.codePointAt(start);
            if (!isWhitespace(codePoint)) {
                break;
            }
            start += Character.charCount(codePoint);
        }

        int end = sourceEnd;
        while (end > start) {
            int codePoint = text.codePointBefore(end);
            if (!isWhitespace(codePoint)) {
                break;
            }
            end -= Character.charCount(codePoint);
        }
        return start == end ? null : new SourceSlice(start, end);
    }

    static List<TextChunk> toTextChunks(ParsedDocument document, List<SourceSlice> sourceSlices,
                                        String strategyName) {
        Objects.requireNonNull(document, "document不能为空");
        Objects.requireNonNull(sourceSlices, "sourceSlices不能为空");
        List<TextChunk> chunks = new ArrayList<>();
        for (SourceSlice sourceSlice : sourceSlices) {
            SourceSlice trimmed = trim(document.text(), sourceSlice.startOffset(), sourceSlice.endOffset());
            if (trimmed == null) {
                continue;
            }
            String content = document.text().substring(trimmed.startOffset(), trimmed.endOffset());
            Trace trace = trace(document, trimmed.startOffset(), trimmed.endOffset());
            Map<String, String> metadata = metadata(document, trimmed, strategyName, trace);
            chunks.add(new TextChunk(chunks.size(), content,
                    TextUnitRanges.count(content, ChunkingUnit.TOKEN), trace.pageFrom(), trace.pageTo(),
                    trace.sectionTitle(), metadata));
        }
        return List.copyOf(chunks);
    }

    private static Trace trace(ParsedDocument document, int startOffset, int endOffset) {
        Integer pageFrom = null;
        Integer pageTo = null;
        for (ParsedPageMapping pageMapping : document.pageMappings()) {
            if (overlaps(startOffset, endOffset, pageMapping.startOffset(), pageMapping.endOffset())) {
                pageFrom = pageFrom == null ? pageMapping.pageNumber() : Math.min(pageFrom, pageMapping.pageNumber());
                pageTo = pageTo == null ? pageMapping.pageNumber() : Math.max(pageTo, pageMapping.pageNumber());
            }
        }

        List<ParsedSection> sections = document.sections().stream()
                .sorted(Comparator.comparingInt(ParsedSection::startOffset)
                        .thenComparingInt(ParsedSection::sequence))
                .toList();
        ParsedSection currentSection = null;
        String sectionTitle = null;
        for (ParsedSection section : sections) {
            if (section.startOffset() > startOffset) {
                break;
            }
            if (section.endOffset() > startOffset) {
                currentSection = section;
            }
            String explicitTitle = section.attributes().get("sectionTitle");
            if (explicitTitle != null && !explicitTitle.isBlank()) {
                sectionTitle = explicitTitle;
            }
            if (section.attributes().containsKey("headingLevel")) {
                String heading = document.textOf(section).strip();
                if (!heading.isBlank()) {
                    sectionTitle = heading;
                }
            }
        }
        if (sectionTitle == null || sectionTitle.isBlank()) {
            sectionTitle = document.metadata().get("title");
        }
        return new Trace(pageFrom, pageTo, limitSectionTitle(sectionTitle), currentSection);
    }

    private static Map<String, String> metadata(ParsedDocument document, SourceSlice sourceSlice,
                                                 String strategyName, Trace trace) {
        Map<String, String> metadata = new TreeMap<>();
        metadata.put("sourceEndOffset", Integer.toString(sourceSlice.endOffset()));
        metadata.put("sourceStartOffset", Integer.toString(sourceSlice.startOffset()));
        metadata.put("strategy", strategyName);
        metadata.put("tokenCountEstimator", "heuristic-v1");
        if (trace.currentSection() != null) {
            metadata.put("sourceSectionKind", trace.currentSection().kind().name());
            metadata.put("sourceSectionSequence", Integer.toString(trace.currentSection().sequence()));
        }
        if (trace.pageFrom() != null) {
            metadata.put("pageFrom", Integer.toString(trace.pageFrom()));
            metadata.put("pageTo", Integer.toString(trace.pageTo()));
        }
        document.metadata().entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .filter(entry -> entry.getValue() != null && !entry.getValue().isBlank())
                .forEach(entry -> metadata.put("documentMetadata." + entry.getKey(), entry.getValue()));
        return metadata;
    }

    private static boolean overlaps(int startOffset, int endOffset, int otherStartOffset, int otherEndOffset) {
        return startOffset < otherEndOffset && otherStartOffset < endOffset;
    }

    private static String limitSectionTitle(String sectionTitle) {
        if (sectionTitle == null || sectionTitle.isBlank()) {
            return null;
        }
        String normalized = sectionTitle.strip();
        if (normalized.length() <= SECTION_TITLE_MAX_LENGTH) {
            return normalized;
        }
        int endOffset = SECTION_TITLE_MAX_LENGTH;
        if (Character.isHighSurrogate(normalized.charAt(endOffset - 1))) {
            endOffset--;
        }
        return normalized.substring(0, endOffset);
    }

    private static boolean isWhitespace(int codePoint) {
        return Character.isWhitespace(codePoint) || Character.isSpaceChar(codePoint);
    }

    private static void requireValidRange(String text, int sourceStart, int sourceEnd) {
        Objects.requireNonNull(text, "text不能为空");
        if (sourceStart < 0 || sourceEnd < sourceStart || sourceEnd > text.length()) {
            throw new IllegalArgumentException("文本区间不合法");
        }
    }

    record SourceSlice(int startOffset, int endOffset) {

        SourceSlice {
            if (startOffset < 0 || endOffset < startOffset) {
                throw new IllegalArgumentException("文本区间不合法");
            }
        }
    }

    private record Trace(Integer pageFrom, Integer pageTo, String sectionTitle, ParsedSection currentSection) {
    }
}
