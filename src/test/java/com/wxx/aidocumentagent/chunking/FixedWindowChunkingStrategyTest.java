package com.wxx.aidocumentagent.chunking;

import java.util.List;
import java.util.Map;

import com.wxx.aidocumentagent.document.parser.ParsedDocument;
import com.wxx.aidocumentagent.document.parser.ParsedPageMapping;
import com.wxx.aidocumentagent.document.parser.ParsedSection;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class FixedWindowChunkingStrategyTest {

    private final FixedWindowChunkingStrategy strategy = new FixedWindowChunkingStrategy();

    @Test
    void 按估算Token切分英文并保留重叠文本() {
        List<TextChunk> chunks = strategy.split(document("one two three four five"),
                new ChunkingOptions(ChunkingUnit.TOKEN, 2, 1));

        assertThat(chunks).extracting(TextChunk::content)
                .containsExactly("one two", "two three", "three four", "four five");
        assertThat(chunks).extracting(TextChunk::tokenCount).containsOnly(2);
        assertThat(chunks).extracting(TextChunk::chunkIndex).containsExactly(0, 1, 2, 3);
    }

    @Test
    void 中文无空格文本也按Token窗口稳定切分() {
        List<TextChunk> chunks = strategy.split(document("中文无空格文本切分"),
                new ChunkingOptions(ChunkingUnit.TOKEN, 4, 1));

        assertThat(chunks).extracting(TextChunk::content)
                .containsExactly("中文无空", "空格文本", "本切分");
        assertThat(chunks).extracting(TextChunk::tokenCount).containsExactly(4, 4, 3);
    }

    @Test
    void 短文本只有一个chunk且内容摘要可复现() {
        ParsedDocument document = document("短文");
        ChunkingOptions options = new ChunkingOptions(ChunkingUnit.CHARACTER, 20, 0);

        List<TextChunk> first = strategy.split(document, options);
        List<TextChunk> second = strategy.split(document, options);

        assertThat(first).hasSize(1);
        assertThat(first.getFirst().content()).isEqualTo("短文");
        assertThat(first).isEqualTo(second);
        assertThat(first.getFirst().contentHash()).isEqualTo(second.getFirst().contentHash()).hasSize(64);
    }

    @Test
    void 多页chunk保留页码范围和章节来源() {
        String text = "章节一\n\n第一页内容\n\n第二页内容";
        ParsedDocument document = new ParsedDocument(text,
                List.of(
                        new ParsedSection(1, ParsedSection.Kind.PARAGRAPH, null, 0, 3,
                                Map.of("headingLevel", "1")),
                        new ParsedSection(2, ParsedSection.Kind.PARAGRAPH, 1, 5, 10, Map.of()),
                        new ParsedSection(3, ParsedSection.Kind.PARAGRAPH, 2, 12, text.length(), Map.of())),
                Map.of("title", "文档标题"),
                List.of(new ParsedPageMapping(1, 5, 10), new ParsedPageMapping(2, 12, text.length())), List.of());

        List<TextChunk> chunks = strategy.split(document, new ChunkingOptions(ChunkingUnit.CHARACTER, 13, 0));

        assertThat(chunks).hasSize(2);
        assertThat(chunks.getFirst())
                .extracting(TextChunk::pageFrom, TextChunk::pageTo, TextChunk::sectionTitle)
                .containsExactly(1, 2, "章节一");
        assertThat(chunks.getFirst().metadata())
                .containsEntry("sourceStartOffset", "0")
                .containsEntry("strategy", FixedWindowChunkingStrategy.STRATEGY_NAME);
        assertThat(chunks.get(1)).extracting(TextChunk::pageFrom, TextChunk::pageTo).containsExactly(2, 2);
    }

    @Test
    void 空白输入不会产生空chunk() {
        List<TextChunk> chunks = strategy.split(document(" \n\n\t "),
                new ChunkingOptions(ChunkingUnit.CHARACTER, 2, 0));

        assertThat(chunks).isEmpty();
    }

    @Test
    void overlap必须小于chunkSize() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new ChunkingOptions(ChunkingUnit.TOKEN, 4, 4))
                .withMessage("overlap必须小于chunkSize");
    }

    private ParsedDocument document(String text) {
        return new ParsedDocument(text, List.of(), Map.of(), List.of(), List.of());
    }
}
