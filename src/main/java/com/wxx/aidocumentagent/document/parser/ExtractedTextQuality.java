package com.wxx.aidocumentagent.document.parser;

/** Detects a broken text layer without assuming a particular document language. */
public final class ExtractedTextQuality {

    private ExtractedTextQuality() {
    }

    public static boolean isUnreadable(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        long characters = 0;
        long unusable = 0;
        for (int offset = 0; offset < text.length();) {
            int codePoint = text.codePointAt(offset);
            offset += Character.charCount(codePoint);
            if (isUnusable(codePoint)) {
                unusable++;
                characters++;
            }
            else if (!Character.isWhitespace(codePoint) && !Character.isSpaceChar(codePoint)) {
                characters++;
            }
        }
        // Occasional extraction defects remain warnings; a damaged text layer must not become evidence.
        return unusable >= 8 && unusable * 5 >= characters;
    }

    static boolean containsUnusableCharacters(String text) {
        return text != null && text.codePoints().anyMatch(ExtractedTextQuality::isUnusable);
    }

    private static boolean isUnusable(int codePoint) {
        return codePoint == 0xFFFD
                || Character.getType(codePoint) == Character.SURROGATE
                || (Character.isISOControl(codePoint) && codePoint != '\t'
                    && codePoint != '\n' && codePoint != '\r' && codePoint != '\f');
    }
}
