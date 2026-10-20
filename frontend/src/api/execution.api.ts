import { ApiClientError, apiRequest } from './client';
import type {
  CreateExecutionRequest,
  ExecutionListParams,
  ExecutionResponse,
  ExecutionStatus,
  ExecutionSummary,
  PageResponse,
} from './contracts';
import { isPositiveId, isUuid } from './progress.contracts';

const statuses: ExecutionStatus[] = [
  'PENDING',
  'RUNNING',
  'PAUSED',
  'SUCCEEDED',
  'FAILED',
  'CANCELLED',
];

export const executionApi = {
  async list(params: ExecutionListParams, signal?: AbortSignal) {
    // The server applies pagination, status/mode filters and stable recent ordering.
    const response = await apiRequest<PageResponse<ExecutionSummary>>({
      method: 'GET',
      url: '/agent/executions',
      params,
      signal,
    });
    if (
      !response ||
      !Array.isArray(response.content) ||
      !Number.isSafeInteger(response.totalElements) ||
      response.totalElements < 0 ||
      response.page !== params.page ||
      response.size !== params.size ||
      !Number.isSafeInteger(response.totalPages) ||
      response.totalPages < 0 ||
      response.content.some(
        (item) =>
          !item ||
          !isUuid(item.executionId) ||
          !isPositiveId(item.knowledgeBaseId) ||
          !statuses.includes(item.status) ||
          !['RETRIEVAL_FIRST', 'REACT', 'PLAN_EXECUTE_REFLECT'].includes(item.agentMode),
      )
    ) {
      throw invalidResponse();
    }
    return response;
  },

  async create(input: CreateExecutionRequest, idempotencyKey: string) {
    const response = await apiRequest<ExecutionResponse>({
      method: 'POST',
      url: '/agent/executions',
      headers: { 'Idempotency-Key': idempotencyKey },
      data: {
        knowledgeBaseId: input.knowledgeBaseId,
        conversationId: input.conversationId,
        agentMode: input.agentMode,
        question: input.question.trim(),
      },
    });
    assertExecution(response, input.knowledgeBaseId);
    if (
      response.conversationId !== input.conversationId ||
      response.agentMode !== input.agentMode
    ) {
      throw invalidResponse();
    }
    return response;
  },

  async get(executionId: string, knowledgeBaseId: number, signal?: AbortSignal) {
    const response = await apiRequest<ExecutionResponse>({
      method: 'GET',
      url: `/agent/executions/${encodeURIComponent(executionId)}`,
      params: { knowledgeBaseId },
      signal,
    });
    assertExecution(response, knowledgeBaseId);
    if (response.executionId !== executionId) throw invalidResponse();
    return response;
  },

  resume(executionId: string, knowledgeBaseId: number) {
    return executionAction('resume', executionId, knowledgeBaseId);
  },

  cancel(executionId: string, knowledgeBaseId: number) {
    return executionAction('cancel', executionId, knowledgeBaseId);
  },
};

async function executionAction(
  action: 'resume' | 'cancel',
  executionId: string,
  knowledgeBaseId: number,
) {
  const response = await apiRequest<ExecutionResponse>({
    method: 'POST',
    url: `/agent/executions/${encodeURIComponent(executionId)}/${action}`,
    params: { knowledgeBaseId },
  });
  assertExecution(response, knowledgeBaseId);
  if (response.executionId !== executionId) throw invalidResponse();
  return response;
}

function assertExecution(response: ExecutionResponse, knowledgeBaseId: number) {
  if (
    !response ||
    typeof response.executionId !== 'string' ||
    !isUuid(response.executionId) ||
    response.knowledgeBaseId !== knowledgeBaseId ||
    !statuses.includes(response.status) ||
    !['RETRIEVAL_FIRST', 'REACT', 'PLAN_EXECUTE_REFLECT'].includes(response.agentMode)
  ) {
    throw invalidResponse();
  }
}

function invalidResponse() {
  return new ApiClientError({
    code: 'INVALID_API_RESPONSE',
    message: '服务返回的执行任务数据不符合当前请求，请刷新后重试。',
  });
}
