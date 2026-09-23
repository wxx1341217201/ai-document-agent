import { HttpResponse, http } from 'msw';
import { describe, expect, it } from 'vitest';
import type { ApiResponse, RagQueryResponse } from './contracts';
import { ragApi } from './rag.api';
import { server } from '../test/mocks/server';

describe('ragApi', () => {
  it('sends the documented non-streaming payload and preserves backend citations', async () => {
    server.use(
      http.post('*/api/v1/knowledge-bases/1/query', async ({ request }) => {
        expect(await request.json()).toEqual({
          question: '系统如何处理重复消息？',
          topK: 8,
          rerank: true,
          stream: false,
        });

        const data: RagQueryResponse = {
          answer: '通过幂等键跳过重复消息。[C1]',
          citations: [
            {
              citationId: 'C1',
              documentId: 101,
              documentName: 'design.pdf',
              chunkId: 1001,
              pageFrom: 5,
              pageTo: 5,
              quote: '消费者会先检查幂等键。',
            },
          ],
          retrieval: { degraded: false, candidateCount: 1 },
        };
        const body: ApiResponse<RagQueryResponse> = {
          code: 'SUCCESS',
          message: 'OK',
          data,
          traceId: 'rag-test-trace',
        };
        return HttpResponse.json(body);
      }),
    );

    const response = await ragApi.query(1, {
      question: '  系统如何处理重复消息？  ',
      topK: 8,
      rerank: true,
    });

    expect(response.answer).toContain('[C1]');
    expect(response.citations).toEqual([
      {
        citationId: 'C1',
        documentId: 101,
        documentName: 'design.pdf',
        chunkId: 1001,
        pageFrom: 5,
        pageTo: 5,
        quote: '消费者会先检查幂等键。',
      },
    ]);
  });
});
