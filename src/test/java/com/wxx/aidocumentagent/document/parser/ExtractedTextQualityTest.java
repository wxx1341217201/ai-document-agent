package com.wxx.aidocumentagent.document.parser;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ExtractedTextQualityTest {

    @Test
    void rejectsBrokenGlyphMappingsEvenWhenPageNumbersAreReadable() {
        String text = "© 2013\n" + "\u0002\u0003\u0004\u0005\u0006\n12\n".repeat(40);
        assertThat(ExtractedTextQuality.isUnreadable(text)).isTrue();
        assertThat(ExtractedTextQuality.isUnreadable("\uFFFD".repeat(30))).isTrue();
        assertThat(ExtractedTextQuality.isUnreadable("\u0002".repeat(8) + " ".repeat(200))).isTrue();
    }

    @Test
    void acceptsReadableLanguagesFormulasAndNormalWhitespace() {
        for (String text : new String[]{"贾宝玉是贾政与王夫人的儿子。", "賈寶玉是賈政與王夫人的兒子。",
                "An English paragraph.\n\nNext page.\t42\f", "العربية Ελληνικά 日本語 한국어 😀", "x² + y² = z²"}) {
            assertThat(ExtractedTextQuality.isUnreadable(text)).isFalse();
        }
    }

    @Test
    void isolatedDefectsDoNotDiscardOtherwiseReadableText() {
        String text = "正常可读正文。".repeat(40) + "\uFFFD\u0002";
        assertThat(ExtractedTextQuality.containsUnusableCharacters(text)).isTrue();
        assertThat(ExtractedTextQuality.isUnreadable(text)).isFalse();
    }
}
