package com.wxx.aidocumentagent.progress;

public interface ProgressSequenceStore {
    long next(ProgressStream stream);
    long current(ProgressStream stream);
}
