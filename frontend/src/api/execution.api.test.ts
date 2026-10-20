import { HttpResponse, http } from 'msw';
import { describe, expect, it } from 'vitest';
import { addConversation } from '../test/mocks/conversation-fixtures';
import { mockExecution } from '../test/mocks/execution-fixtures';
import { server } from '../test/mocks/server';
import type { CreateExecutionRequest } from './contracts';
import { executionApi } from './execution.api';
import { queryKeys } from './query-keys';

function input(): CreateExecutionRequest {
  return {
    knowledgeBaseId: 1,
    conversationId: addConversation(1, { agentMode: 'REACT' }).id,
    agentMode: 'REACT',
    question: '问题',
  };
}

describe('executionApi (MSW contract tests)', () => {
  it('uses the documented list filters and scoped resume/cancel contracts', async () => {
    const page = await executionApi.list({
      page: 0,
      size: 20,
      status: 'PAUSED',
      agentMode: 'REACT',
    });
    expect(page.content).toHaveLength(1);
    const id = page.content[0].executionId;
    const resumed = await executionApi.resume(id, 1);
    expect(resumed.status).toBe('RUNNING');
    await expect(executionApi.resume(id, 1)).rejects.toMatchObject({
      httpStatus: 409,
      code: 'STATE_CONFLICT',
    });
    const cancelling = await executionApi.cancel(id, 1);
    expect(cancelling.status).toBe('RUNNING');
    expect(cancelling.cancelRequested).toBe(true);
    expect((await executionApi.get(id, 1)).status).toBe('CANCELLED');
  });

  it('accepts 202 creation and 200 idempotent reuse, and reports conflicts for a different payload', async () => {
    const request = input();
    const first = await executionApi.create(request, 'same-request');
    expect(first.status).toBe('PENDING');
    expect(await executionApi.create(request, 'same-request')).toEqual(first);
    await expect(
      executionApi.create({ ...request, question: '另一个问题' }, 'same-request'),
    ).rejects.toMatchObject({ httpStatus: 409, code: 'REQUEST_KEY_CONFLICT' });
  });

  it('GET requires the real knowledgeBaseId query param and reads the returned final result', async () => {
    const execution = await executionApi.create(input(), 'scoped-query');
    const result = await executionApi.get(execution.executionId, 1);
    expect(result.status).toBe('SUCCEEDED');
    expect(result.result).toMatchObject({ status: 'COMPLETED', modelName: 'mock-agent-model' });
    await expect(executionApi.get(execution.executionId, 2)).rejects.toMatchObject({
      httpStatus: 404,
      code: 'NOT_FOUND',
    });
    expect(queryKeys.executions.detail(1, execution.executionId)).not.toEqual(
      queryKeys.executions.detail(2, execution.executionId),
    );
  });

  it.each(['knowledgeBaseId', 'conversationId', 'agentMode', 'status', 'executionId'] as const)(
    'rejects a mismatched %s in the create response',
    async (field) => {
      const request = input();
      const invalid = {
        knowledgeBaseId: 2,
        conversationId: 999,
        agentMode: 'PLAN_EXECUTE_REFLECT',
        status: 'QUEUED',
        executionId: '',
      };
      server.use(
        http.post('*/api/v1/agent/executions', () =>
          HttpResponse.json(
            {
              code: 'SUCCESS',
              message: 'OK',
              data: { ...mockExecution(request), [field]: invalid[field] },
            },
            { status: 202 },
          ),
        ),
      );
      await expect(executionApi.create(request, 'mismatch')).rejects.toMatchObject({
        code: 'INVALID_API_RESPONSE',
      });
    },
  );

  it('rejects a GET response from a different execution instead of overwriting its association', async () => {
    const request = input();
    const execution = mockExecution(request);
    server.use(
      http.get('*/api/v1/agent/executions/:id', () =>
        HttpResponse.json({ code: 'SUCCESS', message: 'OK', data: mockExecution(request) }),
      ),
    );
    await expect(executionApi.get(execution.executionId, 1)).rejects.toMatchObject({
      code: 'INVALID_API_RESPONSE',
    });
  });
});
