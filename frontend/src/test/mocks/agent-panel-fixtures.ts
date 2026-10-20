import type { ExecutionResponse } from '../../api/contracts';
import { completeMockExecution, mockExecution } from './execution-fixtures';

/** FE09 UI fixtures use the actual ExecutionResponse fields; no model/backend is invoked. */
export function mockPlanExecution(): ExecutionResponse {
  return {
    ...mockExecution({
      knowledgeBaseId: 1,
      conversationId: 303,
      agentMode: 'PLAN_EXECUTE_REFLECT',
      question: '比较文档中的重试策略',
    }),
    status: 'RUNNING',
    currentStep: 'r0_S2',
    steps: [
      {
        stepId: 'agent_run',
        kind: 'AGENT',
        operation: 'execute',
        status: 'RUNNING',
        dependsOn: null,
        errorCode: null,
      },
      {
        stepId: 'r0_plan',
        kind: 'PLAN',
        operation: 'validated_plan',
        status: 'SUCCEEDED',
        dependsOn: [],
        errorCode: null,
      },
      {
        stepId: 'r0_S1',
        kind: 'TOOL',
        operation: 'SEARCH',
        status: 'SUCCEEDED',
        dependsOn: [],
        errorCode: null,
      },
      {
        stepId: 'r0_S2',
        kind: 'TOOL',
        operation: 'FETCH_CONTEXT',
        status: 'RUNNING',
        dependsOn: ['r0_S1'],
        errorCode: null,
      },
      {
        stepId: 'r0_S3',
        kind: 'GENERATION',
        operation: 'SYNTHESIZE',
        status: 'PENDING',
        dependsOn: ['r0_S1', 'r0_S2'],
        errorCode: null,
      },
    ],
    toolCalls: [
      {
        invocationKey: 'mock-lease:r0_S1',
        toolName: 'searchKnowledgeBase',
        status: 'SUCCEEDED',
        argumentsSummary: { queryLength: '12', topK: '8' },
        elapsedMillis: 320,
        errorCode: null,
      },
    ],
  };
}

export function completeMockPlan(execution: ExecutionResponse): ExecutionResponse {
  return {
    ...completeMockExecution(execution),
    currentStep: 'r0_reflect',
    steps: [
      ...execution.steps.map((step) => ({ ...step, status: 'SUCCEEDED' })),
      {
        stepId: 'r0_reflect',
        kind: 'REFLECTION',
        operation: 'coverage_complete',
        status: 'SUCCEEDED',
        dependsOn: [],
        errorCode: null,
      },
    ],
    toolCalls: [
      ...execution.toolCalls,
      {
        invocationKey: 'mock-lease:r0_S2',
        toolName: 'getChunkContext',
        status: 'SUCCEEDED',
        argumentsSummary: { chunkId: '1001', radius: '1' },
        elapsedMillis: 42,
        errorCode: null,
      },
    ],
  };
}
