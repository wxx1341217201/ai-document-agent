package com.wxx.aidocumentagent.chunking;

import java.util.List;
import java.util.Objects;

import com.wxx.aidocumentagent.document.parser.ParsedDocument;
import org.springframework.stereotype.Component;

/**
 * 按字符或估算 token 的固定窗口切分。窗口之间保留由 {@link ChunkingOptions#overlap()} 指定的重叠。
 */
@Component
public final class FixedWindowChunkingStrategy implements ChunkingStrategy {

    public static final String STRATEGY_NAME = "fixed-window";

    @Override
    public String name() {
        return STRATEGY_NAME;
    }

    @Override
    public List<TextChunk> split(ParsedDocument document, ChunkingOptions options) {
        Objects.requireNonNull(document, "document不能为空");
        Objects.requireNonNull(options, "options不能为空");
        return ChunkingSupport.toTextChunks(document,
                ChunkingSupport.fixedWindowSlices(document.text(), 0, document.text().length(), options), name());
    }
}
