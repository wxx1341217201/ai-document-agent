package com.wxx.aidocumentagent.chunking;

import java.util.List;

import com.wxx.aidocumentagent.document.parser.ParsedDocument;

/**
 * 将已解析文档转换成可索引的、带来源信息的稳定文本块。
 */
public interface ChunkingStrategy {

    String name();

    List<TextChunk> split(ParsedDocument document, ChunkingOptions options);
}
