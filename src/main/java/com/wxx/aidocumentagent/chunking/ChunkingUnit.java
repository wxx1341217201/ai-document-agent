package com.wxx.aidocumentagent.chunking;

/**
 * 固定窗口的度量单位。TOKEN 使用本模块提供的确定性估算器，而不是远程模型 tokenizer。
 */
public enum ChunkingUnit {

    CHARACTER,
    TOKEN
}
