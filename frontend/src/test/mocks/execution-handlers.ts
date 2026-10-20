import { HttpResponse, http } from 'msw';
import type { CreateExecutionRequest } from '../../api/contracts';
import { getConversation } from './conversation-fixtures';
import {
  changeMockExecution,
  createMockExecution,
  getMockExecution,
  listMockExecutions,
} from './execution-fixtures';
import { getKnowledgeBase } from './fixtures';

export const executionHandlers = [
  // Planned §8.5 list API: Mock only, not implemented by ExecutionController.
  http.get('*/api/v1/agent/executions', ({ request }) => {
    const params = new URL(request.url).searchParams;
    const page = Number(params.get('page') ?? 0);
    const size = Number(params.get('size') ?? 20);
    const items = listMockExecutions().filter(
      (item) =>
        (!params.get('status') || params.get('status') === item.status) &&
        (!params.get('agentMode') || params.get('agentMode') === item.agentMode),
    );
    return success({
      content: items.slice(page * size, (page + 1) * size),
      page,
      size,
      totalElements: items.length,
      totalPages: Math.ceil(items.length / size),
    });
  }),
  ...(['resume', 'cancel'] as const).map((action) =>
    http.post(`*/api/v1/agent/executions/:executionId/${action}`, ({ params, request }) => {
      const knowledgeBaseId = Number(new URL(request.url).searchParams.get('knowledgeBaseId'));
      const result = changeMockExecution(String(params.executionId), knowledgeBaseId, action);
      if (!result.execution)
        return failure(
          result.status ?? 409,
          result.status === 404 ? 'NOT_FOUND' : 'STATE_CONFLICT',
          '执行状态不允许此操作',
        );
      return success(result.execution, action === 'resume' ? 202 : 200);
    }),
  ),
  http.post('*/api/v1/agent/executions', async ({ request }) => {
    const input = (await request.json()) as CreateExecutionRequest;
    if (!getKnowledgeBase(input.knowledgeBaseId)) {
      return failure(404, 'KNOWLEDGE_BASE_NOT_FOUND', '知识库不存在');
    }
    const conversation = getConversation(input.conversationId);
    if (
      !conversation ||
      conversation.knowledgeBaseId !== input.knowledgeBaseId ||
      conversation.agentMode !== input.agentMode
    ) {
      return failure(404, 'CONVERSATION_NOT_FOUND', '会话不存在');
    }
    if (
      typeof input.question !== 'string' ||
      !input.question.trim() ||
      input.question.length > 2000
    ) {
      return failure(400, 'VALIDATION_ERROR', '问题不能为空且不能超过2000个字符');
    }
    const key = request.headers.get('Idempotency-Key');
    if (!key || !/^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$/.test(key)) {
      return failure(400, 'BAD_REQUEST', '幂等键不合法');
    }
    const result = createMockExecution(input, key);
    if (!result) return failure(409, 'REQUEST_KEY_CONFLICT', '幂等键已用于不同请求');
    return success(result.execution, result.created ? 202 : 200);
  }),
  http.get('*/api/v1/agent/executions/:executionId', ({ params, request }) => {
    const knowledgeBaseId = Number(new URL(request.url).searchParams.get('knowledgeBaseId'));
    if (!Number.isSafeInteger(knowledgeBaseId) || knowledgeBaseId <= 0) {
      return failure(400, 'VALIDATION_ERROR', 'knowledgeBaseId 必须为正整数');
    }
    const execution = getMockExecution(String(params.executionId), knowledgeBaseId);
    return execution ? success(execution) : failure(404, 'NOT_FOUND', '执行不存在');
  }),
];

function success(data: unknown, status = 200) {
  return HttpResponse.json(
    { code: 'SUCCESS', message: 'OK', data, traceId: 'mock-execution' },
    { status },
  );
}

function failure(status: number, code: string, message: string) {
  return HttpResponse.json({ code, message, data: null, traceId: 'mock-execution' }, { status });
}
