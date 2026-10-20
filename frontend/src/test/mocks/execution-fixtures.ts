import type { CreateExecutionRequest, ExecutionResponse } from '../../api/contracts';
import { mockChatCitation } from './conversation-fixtures';

// FE07 in-memory Mock: a GET completes the demo execution, without writing Conversation history.
const executions = new Map<string, ExecutionResponse>();
const requests = new Map<string, { input: string; executionId: string }>();
const heldExecutions = new Set<string>();

export function resetExecutionFixtures() {
  executions.clear();
  requests.clear();
  heldExecutions.clear();
  // Stable demo IDs survive browser refresh by being re-seeded, not by fake persistence.
  for (const [suffix, status] of [
    ['1', 'PAUSED'],
    ['2', 'RUNNING'],
  ] as const) {
    const execution = {
      ...mockExecution({
        knowledgeBaseId: 1,
        conversationId: 303,
        agentMode: 'REACT',
        question: 'Mock 恢复演示',
      }),
      executionId: `11111111-1111-4111-8111-11111111111${suffix}`,
      conversationId: null,
      status,
      retryable: status === 'PAUSED',
      currentStep: 'agent_run',
    };
    executions.set(execution.executionId, execution);
    heldExecutions.add(execution.executionId);
  }
}

export function mockExecution(input: CreateExecutionRequest): ExecutionResponse {
  return {
    executionId: crypto.randomUUID(),
    knowledgeBaseId: input.knowledgeBaseId,
    conversationId: input.conversationId,
    agentMode: input.agentMode,
    status: 'PENDING',
    currentStep: null,
    version: 0,
    deadlineAt: '2026-10-12T08:30:00',
    cancelRequested: false,
    retryable: false,
    result: null,
    errorCode: null,
    steps: [],
    toolCalls: [],
    createdAt: '2026-10-11T08:30:00',
    updatedAt: '2026-10-11T08:30:00',
  };
}

export function createMockExecution(input: CreateExecutionRequest, idempotencyKey: string) {
  const key = input.knowledgeBaseId + ':' + idempotencyKey;
  const serialized = JSON.stringify(input);
  const existing = requests.get(key);
  if (existing) {
    return existing.input === serialized
      ? { created: false, execution: executions.get(existing.executionId)! }
      : undefined;
  }
  const execution = mockExecution(input);
  executions.set(execution.executionId, execution);
  requests.set(key, { input: serialized, executionId: execution.executionId });
  return { created: true, execution };
}

export function completeMockExecution(execution: ExecutionResponse): ExecutionResponse {
  return {
    ...execution,
    status: 'SUCCEEDED',
    version: execution.version + 1,
    currentStep: 'generate_answer',
    updatedAt: '2026-10-11T08:31:00',
    result: {
      status: 'COMPLETED',
      answer:
        '**Mock 执行回答**：执行任务已完成。' +
        (execution.knowledgeBaseId === 1 ? '幂等键避免重复处理。[C1]' : '本知识库没有演示引用。'),
      citations:
        execution.knowledgeBaseId === 1 ? [{ ...mockChatCitation, knowledgeBaseId: 1 }] : [],
      steps: [],
      toolCalls: [],
      modelName: 'mock-agent-model',
      usage: {
        promptTokens: null,
        completionTokens: null,
        totalTokens: null,
        modelCalls: null,
        toolCalls: null,
      },
      failure: null,
    },
  };
}

export function getMockExecution(executionId: string, knowledgeBaseId: number) {
  const execution = executions.get(executionId);
  if (!execution || execution.knowledgeBaseId !== knowledgeBaseId) return undefined;
  if (execution.cancelRequested && execution.status === 'RUNNING') {
    const cancelled = { ...execution, status: 'CANCELLED' as const };
    executions.set(executionId, cancelled);
    return cancelled;
  }
  if (heldExecutions.has(executionId) || !['PENDING', 'RUNNING'].includes(execution.status))
    return execution;
  const completed = completeMockExecution(execution);
  executions.set(executionId, completed);
  return completed;
}

export function listMockExecutions() {
  return [...executions.values()].map((execution) => ({
    executionId: execution.executionId,
    conversationId: execution.conversationId,
    knowledgeBaseId: execution.knowledgeBaseId,
    agentMode: execution.agentMode,
    status: execution.status,
    currentStep: execution.currentStep,
    startedAt: execution.status === 'PENDING' ? null : execution.createdAt,
    updatedAt: execution.updatedAt,
  }));
}

export function changeMockExecution(
  executionId: string,
  knowledgeBaseId: number,
  action: 'resume' | 'cancel',
) {
  const execution = executions.get(executionId);
  if (!execution || execution.knowledgeBaseId !== knowledgeBaseId) return { status: 404 };
  const resumable =
    !execution.cancelRequested &&
    (execution.status === 'PAUSED' || (execution.status === 'FAILED' && execution.retryable));
  const cancellable =
    !execution.cancelRequested && ['PENDING', 'RUNNING', 'PAUSED'].includes(execution.status);
  if (action === 'resume' ? !resumable : !cancellable) return { status: 409 };
  const next = {
    ...execution,
    version: execution.version + 1,
    status:
      action === 'resume'
        ? ('RUNNING' as const)
        : execution.status === 'RUNNING'
          ? ('RUNNING' as const)
          : ('CANCELLED' as const),
    retryable: false,
    cancelRequested: action === 'cancel',
    errorCode: null,
  };
  executions.set(executionId, next);
  return { execution: next };
}
