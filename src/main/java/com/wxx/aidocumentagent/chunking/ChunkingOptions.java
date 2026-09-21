package com.wxx.aidocumentagent.chunking;

import java.util.Objects;

/**
 * 一次文本切分使用的算法参数。
 */
public record ChunkingOptions(ChunkingUnit windowUnit, int chunkSize, int overlap) {

    public ChunkingOptions {
        Objects.requireNonNull(windowUnit, "windowUnit不能为空");
        if (chunkSize <= 0) {
            throw new IllegalArgumentException("chunkSize必须大于0");
        }
        if (overlap < 0) {
            throw new IllegalArgumentException("overlap不能小于0");
        }
        if (overlap >= chunkSize) {
            throw new IllegalArgumentException("overlap必须小于chunkSize");
        }
    }
}
