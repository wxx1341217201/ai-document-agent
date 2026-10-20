import { useQuery, useQueryClient } from '@tanstack/react-query';
import { useCallback, useMemo } from 'react';
import type { ExecutionResponse, ExecutionStatus } from '../../api/contracts';
import { executionApi } from '../../api/execution.api';
import { isPositiveId, isUuid, type ProgressResyncReason } from '../../api/progress.contracts';
import { progressWebSocketClient } from '../../api/progress-websocket';
import { queryKeys } from '../../api/query-keys';
import {
  PROGRESS_POLL_INTERVAL_MS,
  useProgressSubscription,
  type ProgressHookOptions,
} from './useProgressSubscription';

function isTerminal(status: ExecutionStatus): boolean {
  return status === 'SUCCEEDED' || status === 'FAILED' || status === 'CANCELLED';
}

export function useExecutionProgress(
  knowledgeBaseId: number | undefined,
  executionId: string | undefined,
  options: ProgressHookOptions<ExecutionResponse> = {},
) {
  const queryClient = useQueryClient();
  const enabled = options.enabled !== false && isPositiveId(knowledgeBaseId) && isUuid(executionId);
  const client = options.client ?? progressWebSocketClient;
  const target = useMemo(
    () => ({
      resource: 'EXECUTION' as const,
      knowledgeBaseId: knowledgeBaseId ?? -1,
      executionId: executionId ?? '',
    }),
    [knowledgeBaseId, executionId],
  );
  const query = useQuery({
    queryKey: queryKeys.executions.detail(knowledgeBaseId ?? -1, executionId ?? ''),
    queryFn: async ({ signal }) => {
      const sequence = client.getSnapshot(target).sequence;
      const data = await executionApi.get(executionId!, knowledgeBaseId!, signal);
      if (!signal.aborted) client.markSynchronized(target, sequence);
      return data;
    },
    initialData: options.initialData,
    enabled,
    staleTime: PROGRESS_POLL_INTERVAL_MS,
    refetchInterval: (current) =>
      current.state.data && !isTerminal(current.state.data.status)
        ? PROGRESS_POLL_INTERVAL_MS
        : false,
  });
  const conversationId = query.data?.conversationId;
  const synchronize = useCallback(
    async (reason: ProgressResyncReason) => {
      const refreshes = [
        queryClient.invalidateQueries(
          { queryKey: queryKeys.executions.detail(knowledgeBaseId!, executionId!), exact: true },
          { throwOnError: true },
        ),
      ];
      if (reason === 'terminal') {
        refreshes.push(queryClient.invalidateQueries({ queryKey: queryKeys.executions.all }));
        if (conversationId) {
          refreshes.push(
            queryClient.invalidateQueries({ queryKey: queryKeys.messages.list(conversationId) }),
            queryClient.invalidateQueries({
              queryKey: queryKeys.conversations.list(knowledgeBaseId!),
            }),
          );
        }
      }
      await Promise.all(refreshes);
    },
    [queryClient, knowledgeBaseId, executionId, conversationId],
  );
  const progress = useProgressSubscription(
    target,
    enabled && Boolean(query.data) && !isTerminal(query.data!.status),
    synchronize,
    client,
  );
  return { ...query, progress };
}
