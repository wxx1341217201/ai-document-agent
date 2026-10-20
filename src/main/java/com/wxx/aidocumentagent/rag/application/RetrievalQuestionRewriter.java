package com.wxx.aidocumentagent.rag.application;

import java.util.List;

/** 只为检索产生候选问题；回答生成始终使用原始用户问题。 */
@FunctionalInterface
public interface RetrievalQuestionRewriter {

    RagChatCompletion rewrite(String originalQuestion, List<RagConversationTurn> history);
}
