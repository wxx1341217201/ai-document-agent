import { HttpResponse, http } from 'msw';
import { describe, expect, it } from 'vitest';
import { server } from '../test/mocks/server';
import { getConversationMessages } from '../test/mocks/conversation-fixtures';
import { conversationApi } from './conversation.api';
import type { ConversationMessage } from './contracts';

describe('conversationApi (MSW contract tests)', () => {
  it('lists within a knowledge base and creates each supported new mode with the exact DTO', async () => {
    const list = await conversationApi.list(1, { page: 0, size: 20 });
    expect(list.content.map((item) => item.id)).toEqual([302, 301]);
    expect((await conversationApi.list(2, { page: 0, size: 20 })).content).toEqual([]);
    for (const agentMode of ['RETRIEVAL_FIRST', 'REACT', 'PLAN_EXECUTE_REFLECT'] as const) {
      const result = await conversationApi.create(1, { title: '  模式测试  ', agentMode });
      expect(result).toMatchObject({ knowledgeBaseId: 1, title: '模式测试', agentMode });
    }
  });

  it('posts content only, preserves both persisted messages and restores them from history', async () => {
    let payload: unknown;
    server.use(
      http.post('*/api/v1/conversations/302/messages', async ({ request }) => {
        payload = await request.json();
        return HttpResponse.json({
          code: 'SUCCESS',
          message: 'OK',
          data: {
            userMessage: {
              ...getConversationMessages(301)[0],
              conversationId: 302,
              content: '后续问题',
            },
            assistantMessage: { ...getConversationMessages(301)[1], conversationId: 302 },
          },
        });
      }),
    );
    const response = await conversationApi.sendMessage(302, { content: '  后续问题  ' });
    expect(payload).toEqual({ content: '后续问题' });
    expect(response.assistantMessage.citations[0]).toMatchObject({
      citationId: 'C1',
      documentId: 101,
      chunkId: 1001,
    });
    expect(response.assistantMessage.tokenUsage).toBeNull();
    const saved = await conversationApi.sendMessage(301, { content: '请解释重试' });
    expect(await conversationApi.history(301)).toEqual([...getConversationMessages(301)]);
    expect(saved.assistantMessage.sequenceNo).toBe(saved.userMessage.sequenceNo + 1);
  });

  it('reads every ascending history page, orders messages by sequence and removes overlapping IDs', async () => {
    const original = getConversationMessages(301);
    const recent: ConversationMessage = {
      ...original[0],
      id: 3010,
      sequenceNo: 3,
      content: '最后一页',
    };
    const pages: number[] = [];
    server.use(
      http.get('*/api/v1/conversations/301/messages', ({ request }) => {
        const url = new URL(request.url);
        const page = Number(url.searchParams.get('page'));
        expect(url.searchParams.get('size')).toBe('100');
        pages.push(page);
        return HttpResponse.json({
          code: 'SUCCESS',
          message: 'OK',
          data: {
            content: page === 0 ? original : [recent, original[1]],
            page,
            size: 100,
            totalPages: 2,
            totalElements: 3,
          },
        });
      }),
    );
    expect((await conversationApi.history(301)).map((item) => item.sequenceNo)).toEqual([1, 2, 3]);
    expect(pages).toEqual([0, 1]);
  });

  it('deletes a conversation and propagates the backend not-found code', async () => {
    await conversationApi.delete(301);
    await expect(conversationApi.history(301)).rejects.toMatchObject({
      httpStatus: 404,
      code: 'CONVERSATION_NOT_FOUND',
    });
    expect(
      (await conversationApi.list(1, { page: 0, size: 20 })).content.map((item) => item.id),
    ).toEqual([302]);
  });

  it('keeps server errors and trace IDs without retrying the message POST', async () => {
    let requests = 0;
    server.use(
      http.post('*/api/v1/conversations/301/messages', () => {
        requests += 1;
        return HttpResponse.json(
          {
            code: 'CONVERSATION_ANSWERING_UNAVAILABLE',
            message: '会话回答服务暂不可用',
            data: null,
            traceId: 'chat-error',
          },
          { status: 503 },
        );
      }),
    );
    await expect(conversationApi.sendMessage(301, { content: '测试问题' })).rejects.toMatchObject({
      code: 'CONVERSATION_ANSWERING_UNAVAILABLE',
      traceId: 'chat-error',
    });
    expect(requests).toBe(1);
  });
});
