import { HttpResponse, http } from 'msw';
import { describe, expect, it } from 'vitest';
import { addConversation, getConversationMessages } from '../test/mocks/conversation-fixtures';
import { mockExecution } from '../test/mocks/execution-fixtures';
import { server } from '../test/mocks/server';
import { agentGateway } from './agent-gateway';

describe('AgentGateway (MSW contract tests)', () => {
  it.each(['RAG', 'RETRIEVAL_FIRST'] as const)(
    'adapts %s through the synchronous message endpoint only',
    async (agentMode) => {
      let body: unknown;
      let executions = 0;
      server.use(
        http.post('*/api/v1/agent/executions', () => {
          executions += 1;
          return HttpResponse.error();
        }),
        http.post('*/api/v1/conversations/301/messages', async ({ request }) => {
          body = await request.json();
          return success({
            userMessage: getConversationMessages(301)[0],
            assistantMessage: getConversationMessages(301)[1],
          });
        }),
      );
      const result = await agentGateway.sendMessage({
        knowledgeBaseId: 1,
        conversationId: 301,
        agentMode,
        content: '  提问  ',
        clientMessageId: 'sync-turn',
      });
      expect(body).toEqual({ content: '提问' });
      expect(executions).toBe(0);
      expect(result).toEqual({
        kind: 'completed',
        userMessage: getConversationMessages(301)[0],
        message: getConversationMessages(301)[1],
      });
    },
  );

  it.each(['REACT', 'PLAN_EXECUTE_REFLECT'] as const)(
    'adapts %s to a scoped execution and preserves real status and identity',
    async (agentMode) => {
      const conversation = addConversation(1, { agentMode });
      let body: unknown;
      let key: string | null = null;
      let synchronousPosts = 0;
      const execution = {
        ...mockExecution({
          knowledgeBaseId: 1,
          conversationId: conversation.id,
          agentMode,
          question: '问题',
        }),
        status: 'RUNNING' as const,
      };
      server.use(
        http.post('*/api/v1/agent/executions', async ({ request }) => {
          body = await request.json();
          key = request.headers.get('Idempotency-Key');
          return success(execution, 202);
        }),
        http.post('*/api/v1/conversations/:id/messages', () => {
          synchronousPosts += 1;
          return HttpResponse.error();
        }),
      );
      const result = await agentGateway.sendMessage({
        knowledgeBaseId: 1,
        conversationId: conversation.id,
        agentMode,
        content: '  问题  ',
        clientMessageId: 'client-turn-id',
      });
      expect(body).toEqual({
        knowledgeBaseId: 1,
        conversationId: conversation.id,
        agentMode,
        question: '问题',
      });
      expect(key).toBe('client-turn-id');
      expect(synchronousPosts).toBe(0);
      expect(result).toEqual({
        kind: 'execution',
        executionId: execution.executionId,
        status: 'RUNNING',
        execution,
      });
      expect(getConversationMessages(conversation.id)).toEqual([]);
    },
  );

  it('propagates execution errors with trace ID and never retries or falls back to the sync endpoint', async () => {
    let posts = 0;
    let fallbackPosts = 0;
    server.use(
      http.post('*/api/v1/agent/executions', () => {
        posts += 1;
        return HttpResponse.json(
          {
            code: 'STATE_CONFLICT',
            message: '执行状态不允许此操作',
            data: null,
            traceId: 'gateway-error',
          },
          { status: 409 },
        );
      }),
      http.post('*/api/v1/conversations/:id/messages', () => {
        fallbackPosts += 1;
        return HttpResponse.error();
      }),
    );
    await expect(
      agentGateway.sendMessage({
        knowledgeBaseId: 1,
        conversationId: 301,
        agentMode: 'REACT',
        content: '问题',
        clientMessageId: 'failed-turn',
      }),
    ).rejects.toMatchObject({ code: 'STATE_CONFLICT', traceId: 'gateway-error' });
    expect(posts).toBe(1);
    expect(fallbackPosts).toBe(0);
  });
});

function success(data: unknown, status = 200) {
  return HttpResponse.json({ code: 'SUCCESS', message: 'OK', data }, { status });
}
