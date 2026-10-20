import { describe, expect, it } from 'vitest';
import { mockPlanExecution } from '../../test/mocks/agent-panel-fixtures';
import { executionActions, executionDetailUrl } from './execution-state';

describe('backend execution state machine', () => {
  it.each([
    ['PENDING', false, false, true],
    ['RUNNING', false, false, true],
    ['PAUSED', false, true, true],
    ['FAILED', true, true, false],
    ['FAILED', false, false, false],
    ['SUCCEEDED', true, false, false],
    ['CANCELLED', true, false, false],
  ] as const)('%s retryable=%s: resume=%s cancel=%s', (status, retryable, canResume, canCancel) => {
    expect(executionActions({ ...mockPlanExecution(), status, retryable })).toEqual({
      canResume,
      canCancel,
    });
  });
  it('blocks actions after a cancellation request and leaves checkpoint expiry validation to the server', () => {
    expect(
      executionActions({ ...mockPlanExecution(), status: 'PAUSED', cancelRequested: true }),
    ).toEqual({ canResume: false, canCancel: false });
    expect(
      executionActions({ ...mockPlanExecution(), status: 'PAUSED', deadlineAt: '2000-01-01' })
        .canResume,
    ).toBe(true);
  });
  it('includes scope in refreshable detail URLs', () => {
    expect(executionDetailUrl('11111111-1111-4111-8111-111111111111', 7)).toBe(
      '/agent-executions/11111111-1111-4111-8111-111111111111?knowledgeBaseId=7',
    );
  });
});
