import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import type { ExecutionListParams } from '../../api/contracts';
import { executionApi } from '../../api/execution.api';
import { queryKeys } from '../../api/query-keys';

export function useExecutionList(params: ExecutionListParams) {
  return useQuery({
    queryKey: queryKeys.executions.list(params),
    queryFn: ({ signal }) => executionApi.list(params, signal),
    refetchInterval: (query) =>
      query.state.data?.content.some((item) =>
        ['PENDING', 'RUNNING', 'PAUSED'].includes(item.status),
      )
        ? 5_000
        : false,
  });
}

export function useExecutionAction(knowledgeBaseId: number, executionId: string) {
  const client = useQueryClient();
  return useMutation({
    mutationKey: ['execution-action', knowledgeBaseId, executionId],
    retry: false,
    mutationFn: (action: 'resume' | 'cancel') => executionApi[action](executionId, knowledgeBaseId),
    onSuccess: (execution) => {
      client.setQueryData(queryKeys.executions.detail(knowledgeBaseId, executionId), execution);
    },
    // Reconcile even a timeout or 409: the action may already have been accepted.
    onSettled: async () => {
      await Promise.all([
        client.invalidateQueries({
          queryKey: queryKeys.executions.detail(knowledgeBaseId, executionId),
          exact: true,
        }),
        client.invalidateQueries({ queryKey: queryKeys.executions.all }),
      ]);
    },
  });
}
