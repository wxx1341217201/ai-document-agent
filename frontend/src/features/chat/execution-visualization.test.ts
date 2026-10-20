import { describe, expect, it } from 'vitest';
import type { ExecutionResponse } from '../../api/contracts';
import { mockPlanExecution } from '../../test/mocks/agent-panel-fixtures';
import { executionVisualization } from './execution-visualization';

describe('execution visualization safety projection', () => {
  it('keeps real plan order, dependency IDs, AGENT and ReAct COMPLETED without copying extra fields', () => {
    const source = mockPlanExecution();
    const steps = source.steps.map((step, index) => ({
      ...step,
      status: ['PENDING', 'RUNNING', 'COMPLETED', 'FAILED', 'SKIPPED'][index],
      thoughts: 'hidden chain of thought',
      summary: '<think>private</think>',
    }));
    const view = executionVisualization({ ...source, steps });
    expect(view.steps.map((step) => step.stepId)).toEqual(source.steps.map((step) => step.stepId));
    expect(view.steps[0].kind).toBe('AGENT');
    expect(view.steps[2].status).toBe('COMPLETED');
    expect(view.steps[3].dependsOn).toEqual(['r0_S1']);
    expect(JSON.stringify(view)).not.toMatch(/hidden|private|thoughts|summary/);
  });

  it('ignores malformed/free-form steps, unknown statuses, invalid dependencies and duplicate IDs', () => {
    const source = mockPlanExecution();
    const view = executionVisualization({
      ...source,
      currentStep: '<think>private</think>',
      steps: [
        null,
        { ...source.steps[0], operation: '<img src=x onerror=alert(1)>' },
        { ...source.steps[0], status: 'RETRYING' },
        { ...source.steps[0], kind: 'thought' },
        { ...source.steps[1], dependsOn: ['r0_S1', 'r0_S1', '<think>private</think>', {}, 1] },
        source.steps[1],
        { ...source.steps[2], errorCode: 'Error: <think>private</think>' },
      ] as unknown as ExecutionResponse['steps'],
    });
    expect(view.currentStep).toBeNull();
    expect(view.steps.map((step) => step.stepId)).toEqual(['r0_plan', 'r0_S1']);
    expect(view.steps[0].dependsOn).toEqual(['r0_S1']);
    expect(view.steps[1].errorCode).toBeNull();
    expect(JSON.stringify(view)).not.toMatch(/private|onerror|thought/);
  });

  it('allows only the backend numeric argument summary and preserves unknown elapsed time', () => {
    const source = mockPlanExecution();
    const tool = source.toolCalls[0];
    const view = executionVisualization({
      ...source,
      toolCalls: [
        {
          ...tool,
          status: 'FAILED',
          argumentsSummary: {
            queryLength: '12',
            topK: '8',
            documentId: '9007199254740995',
            radius: 2,
            chunkId: '1</think>',
            query: 'private query',
            thought: 'hidden reasoning',
            output: '<img onerror=alert(1)>',
          },
          elapsedMillis: -1,
          errorCode: 'TOOL_TIMEOUT',
        },
        { ...tool, invocationKey: 'another-call', status: 'COMPLETED', elapsedMillis: 0 },
        tool,
        { ...tool, invocationKey: 'unsafe', toolName: '<think>private</think>' },
      ],
    });
    expect(view.toolCalls).toHaveLength(2);
    expect(view.toolCalls[0].arguments).toEqual([
      { label: '检索词长度', value: '12' },
      { label: 'topK', value: '8' },
      { label: '文档 ID', value: '9007199254740995' },
    ]);
    expect(view.toolCalls[0]).toMatchObject({ elapsedMillis: null, errorCode: 'TOOL_TIMEOUT' });
    expect(view.toolCalls[1].elapsedMillis).toBe(0);
    expect(JSON.stringify(view)).not.toMatch(/private|reasoning|onerror/);
  });

  it('treats missing or non-array details as empty rather than falling back to arbitrary result JSON', () => {
    const view = executionVisualization({
      ...mockPlanExecution(),
      steps: null,
      toolCalls: { reasoning: 'private' },
      result: { steps: [{ thought: 'private' }] },
    } as unknown as ExecutionResponse);
    expect(view.steps).toEqual([]);
    expect(view.toolCalls).toEqual([]);
  });
});
