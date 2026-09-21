package com.wxx.aidocumentagent.chunking;

import java.util.List;
import java.util.Map;

import com.wxx.aidocumentagent.document.parser.ParsedDocument;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ParagraphChunkingStrategyTest {

    private final ParagraphChunkingStrategy strategy = new ParagraphChunkingStrategy();

    @Test
    void 优先保留完整段落边界() {
        ParsedDocument document = document("第一段。\n\n第二段。\n\n第三段。");

        List<TextChunk> chunks = strategy.split(document, new ChunkingOptions(ChunkingUnit.CHARACTER, 10, 0));

        assertThat(chunks).extracting(TextChunk::content)
                .containsExactly("第一段。\n\n第二段。", "第三段。");
    }

    @Test
    void 超长段落自动降级为固定窗口并保留重叠() {
        List<TextChunk> chunks = strategy.split(document("超长段落无空格文本切分"),
                new ChunkingOptions(ChunkingUnit.CHARACTER, 4, 1));

        assertThat(chunks).hasSizeGreaterThan(1);
        assertThat(chunks).allSatisfy(chunk -> {
            assertThat(chunk.content()).isNotBlank();
            assertThat(chunk.content().codePointCount(0, chunk.content().length())).isLessThanOrEqualTo(4);
            assertThat(chunk.metadata()).containsEntry("strategy", ParagraphChunkingStrategy.STRATEGY_NAME);
        });
        assertThat(chunks.get(1).content()).startsWith(chunks.getFirst().content().substring(
                chunks.getFirst().content().length() - 1));
    }

    @Test
    void 多空行不会生成空段落或空chunk() {
        List<TextChunk> chunks = strategy.split(document("\n\n第一段\n\n\n第二段\n\n\n\n第三段\n\n"),
                new ChunkingOptions(ChunkingUnit.CHARACTER, 100, 0));

        assertThat(chunks).singleElement().extracting(TextChunk::content)
                .isEqualTo("第一段\n\n\n第二段\n\n\n\n第三段");
    }

    @Test
    void 中文无空格段落和重复执行结果稳定() {
        ParsedDocument document = document("中文无空格文本切分\n\n第二段中文文本");
        ChunkingOptions options = new ChunkingOptions(ChunkingUnit.TOKEN, 5, 1);

        List<TextChunk> first = strategy.split(document, options);
        List<TextChunk> second = strategy.split(document, options);

        assertThat(first).isEqualTo(second);
        assertThat(first).allSatisfy(chunk -> assertThat(chunk.content()).isNotBlank());
        assertThat(first).extracting(TextChunk::chunkIndex).containsExactly(0, 1, 2, 3);
    }

    @Test
    void 短文本保持原样() {
        List<TextChunk> chunks = strategy.split(document("短文"),
                new ChunkingOptions(ChunkingUnit.TOKEN, 10, 1));

        assertThat(chunks).singleElement().extracting(TextChunk::content).isEqualTo("短文");
    }

    private ParsedDocument document(String text) {
        return new ParsedDocument(text, List.of(), Map.of(), List.of(), List.of());
    }
}
