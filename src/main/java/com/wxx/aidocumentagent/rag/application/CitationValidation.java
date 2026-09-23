package com.wxx.aidocumentagent.rag.application;

import java.util.List;

/** 模型答案与当前上下文引用映射的校验结果。 */
record CitationValidation(boolean valid, String answer, List<RagCitation> citations) {

    CitationValidation {
        citations = citations == null ? List.of() : List.copyOf(citations);
    }
}
