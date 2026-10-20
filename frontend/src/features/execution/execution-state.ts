import type { ExecutionResponse } from '../../api/contracts';

/** AgentExecution.claim/requestCancel are the authority for these transitions. */
export function executionActions(execution: ExecutionResponse) {
  const available = !execution.cancelRequested;
  return {
    // Do not infer expiry from the browser clock: a durable result may be finalized
    // after deadlineAt, and the server validates expiry and leases atomically.
    canResume:
      available &&
      (execution.status === 'PAUSED' ||
        (execution.status === 'FAILED' && execution.retryable === true)),
    canCancel: available && ['PENDING', 'RUNNING', 'PAUSED'].includes(execution.status),
  };
}

export function executionDetailUrl(executionId: string, knowledgeBaseId: number) {
  return `/agent-executions/${encodeURIComponent(executionId)}?knowledgeBaseId=${knowledgeBaseId}`;
}
