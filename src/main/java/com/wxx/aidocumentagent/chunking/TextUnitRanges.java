package com.wxx.aidocumentagent.chunking;

import java.util.ArrayList;
import java.util.List;

/**
 * 为窗口切分提供 UTF-16 字符偏移。TOKEN 是稳定的启发式估算：中文、日文和韩文按字符计，
 * 拉丁字母或数字连续串计一个 token，其余非空白字符各计一个 token。
 */
final class TextUnitRanges {

    private TextUnitRanges() {
    }

    static List<OffsetRange> forUnit(String text, ChunkingUnit unit) {
        return switch (unit) {
            case CHARACTER -> characterRanges(text);
            case TOKEN -> tokenRanges(text);
        };
    }

    static int count(String text, ChunkingUnit unit) {
        return forUnit(text, unit).size();
    }

    private static List<OffsetRange> characterRanges(String text) {
        List<OffsetRange> ranges = new ArrayList<>();
        for (int offset = 0; offset < text.length();) {
            int nextOffset = offset + Character.charCount(text.codePointAt(offset));
            ranges.add(new OffsetRange(offset, nextOffset));
            offset = nextOffset;
        }
        return ranges;
    }

    private static List<OffsetRange> tokenRanges(String text) {
        List<OffsetRange> ranges = new ArrayList<>();
        for (int offset = 0; offset < text.length();) {
            int codePoint = text.codePointAt(offset);
            int nextOffset = offset + Character.charCount(codePoint);
            if (isWhitespace(codePoint)) {
                offset = nextOffset;
            }
            else if (isWordCodePoint(codePoint) && !isCjkCodePoint(codePoint)) {
                int wordStart = offset;
                int wordEnd = nextOffset;
                while (wordEnd < text.length()) {
                    int nextCodePoint = text.codePointAt(wordEnd);
                    if (!isWordCodePoint(nextCodePoint) || isCjkCodePoint(nextCodePoint)) {
                        break;
                    }
                    wordEnd += Character.charCount(nextCodePoint);
                }
                ranges.add(new OffsetRange(wordStart, wordEnd));
                offset = wordEnd;
            }
            else {
                ranges.add(new OffsetRange(offset, nextOffset));
                offset = nextOffset;
            }
        }
        return ranges;
    }

    private static boolean isWhitespace(int codePoint) {
        return Character.isWhitespace(codePoint) || Character.isSpaceChar(codePoint);
    }

    private static boolean isWordCodePoint(int codePoint) {
        return Character.isLetterOrDigit(codePoint) || codePoint == '_';
    }

    private static boolean isCjkCodePoint(int codePoint) {
        return (codePoint >= 0x3040 && codePoint <= 0x30ff)
                || (codePoint >= 0x3100 && codePoint <= 0x312f)
                || (codePoint >= 0x3400 && codePoint <= 0x4dbf)
                || (codePoint >= 0x4e00 && codePoint <= 0x9fff)
                || (codePoint >= 0xac00 && codePoint <= 0xd7af)
                || (codePoint >= 0xf900 && codePoint <= 0xfaff)
                || (codePoint >= 0x20000 && codePoint <= 0x2fa1f);
    }

    record OffsetRange(int startOffset, int endOffset) {
    }
}
