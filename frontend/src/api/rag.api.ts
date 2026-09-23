import { apiRequest } from './client';
import type { RagQueryRequest, RagQueryResponse } from './contracts';

function resourcePath(knowledgeBaseId: number) {
  return `/knowledge-bases/${knowledgeBaseId}/query`;
}

export const ragApi = {
  query(knowledgeBaseId: number, input: RagQueryRequest) {
    return apiRequest<RagQueryResponse>({
      method: 'POST',
      url: resourcePath(knowledgeBaseId),
      data: {
        question: input.question.trim(),
        topK: input.topK,
        rerank: input.rerank,
        // M11 only defines the non-streaming protocol. Do not allow this UI to request SSE.
        stream: false,
      },
    });
  },
};
