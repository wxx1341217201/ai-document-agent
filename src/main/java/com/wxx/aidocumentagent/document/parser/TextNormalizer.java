package com.wxx.aidocumentagent.document.parser;

/**
 * 统一行结束符和水平空白，同时最多保留一个空行以维持段落边界。
 */
final class TextNormalizer {

    private TextNormalizer() {
    }

    static String normalize(CharSequence value) {
        NormalizingAppender appender = new NormalizingAppender(value == null ? 0 : value.length());
        if (value != null) {
            appender.append(value);
        }
        return appender.finish();
    }

    static boolean containsReplacementCharacter(String value) {
        return value.indexOf('\uFFFD') >= 0;
    }

    static final class NormalizingAppender {

        private final StringBuilder result;
        private boolean pendingSpace;
        private boolean previousWasCarriageReturn;
        private int consecutiveLineBreaks;

        NormalizingAppender(int expectedLength) {
            this.result = new StringBuilder(Math.max(16, expectedLength));
        }

        void append(CharSequence value) {
            for (int index = 0; index < value.length(); index++) {
                append(value.charAt(index));
            }
        }

        void append(char[] value, int offset, int length) {
            for (int index = offset; index < offset + length; index++) {
                append(value[index]);
            }
        }

        private void append(char character) {
            if (previousWasCarriageReturn && character == '\n') {
                previousWasCarriageReturn = false;
                return;
            }
            previousWasCarriageReturn = false;

            if (isLineBreak(character)) {
                appendLineBreak();
                previousWasCarriageReturn = character == '\r';
                return;
            }
            if (character == '\uFEFF') {
                return;
            }
            if (isHorizontalWhitespace(character)) {
                pendingSpace = result.length() > 0 && result.charAt(result.length() - 1) != '\n';
                return;
            }
            if (pendingSpace && result.length() > 0 && result.charAt(result.length() - 1) != '\n') {
                result.append(' ');
            }
            result.append(character);
            pendingSpace = false;
            consecutiveLineBreaks = 0;
        }

        private void appendLineBreak() {
            pendingSpace = false;
            while (result.length() > 0 && result.charAt(result.length() - 1) == ' ') {
                result.deleteCharAt(result.length() - 1);
            }
            if (result.length() == 0) {
                return;
            }
            if (consecutiveLineBreaks < 2) {
                result.append('\n');
            }
            consecutiveLineBreaks++;
        }

        String finish() {
            int end = result.length();
            while (end > 0 && (result.charAt(end - 1) == ' ' || result.charAt(end - 1) == '\n')) {
                end--;
            }
            if (end < result.length()) {
                result.setLength(end);
            }
            return result.toString();
        }

        private boolean isLineBreak(char character) {
            return character == '\r' || character == '\n' || character == '\u2028' || character == '\u2029';
        }

        private boolean isHorizontalWhitespace(char character) {
            return Character.isWhitespace(character) || Character.isSpaceChar(character) || character == '\u00A0';
        }
    }
}
