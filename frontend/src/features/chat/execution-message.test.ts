import { describe, expect, it } from 'vitest';
import { completeMockExecution, mockExecution } from '../../test/mocks/execution-fixtures';
import { executionMessage } from './execution-message';

function completed() {
  return completeMockExecution(
    mockExecution({
      knowledgeBaseId: 1,
      conversationId: 303,
      agentMode: 'REACT',
      question: '问题',
    }),
  );
}

describe('execution answer projection', () => {
  it('projects real final fields without inventing ConversationMessage IDs or copying internal fields', () => {
    const execution = completed();
    const result = execution.result as Record<string, unknown>;
    const message = executionMessage({
      ...execution,
      result: { ...result, thoughts: 'hidden reasoning', internal: { secret: 'hidden' } },
    });
    expect(message).toMatchObject({
      role: 'ASSISTANT',
      modelName: 'mock-agent-model',
      tokenUsage: { totalTokens: null },
      createdAt: execution.updatedAt,
    });
    expect(message).not.toHaveProperty('id');
    expect(message).not.toHaveProperty('sequenceNo');
    expect(JSON.stringify(message)).not.toContain('hidden');
  });

  it.each([
    null,
    {},
    { status: 'COMPLETED', answer: '' },
    { status: 'FAILED', answer: '不应展示' },
    { status: 'COMPLETED', answer: '<think>hidden</think>回答' },
  ])('does not render an absent, invalid or hidden reasoning result', (result) => {
    expect(executionMessage({ ...completed(), result })).toBeUndefined();
  });

  it('does not render checkpointed answers before SUCCEEDED, or accept citations from a different KB', () => {
    const execution = completed();
    expect(executionMessage({ ...execution, status: 'RUNNING' })).toBeUndefined();
    const result = execution.result as { citations: Record<string, unknown>[] };
    const message = executionMessage({
      ...execution,
      result: {
        ...(execution.result as object),
        citations: [
          { ...result.citations[0], knowledgeBaseId: 2 },
          { ...result.citations[0], chunkId: -1 },
          { ...result.citations[0], pageTo: 1 },
        ],
      },
    });
    expect(message?.citations).toEqual([]);
  });
});
