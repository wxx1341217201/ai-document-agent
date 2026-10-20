import { useQuery, useQueryClient } from '@tanstack/react-query';
import { useCallback, useMemo } from 'react';
import type { DocumentRecord } from '../../api/contracts';
import { documentApi } from '../../api/document.api';
import { isPositiveId } from '../../api/progress.contracts';
import { progressWebSocketClient } from '../../api/progress-websocket';
import { queryKeys } from '../../api/query-keys';
import { isDocumentProcessing } from '../../utils/document-status';
import {
  PROGRESS_POLL_INTERVAL_MS,
  useProgressSubscription,
  type ProgressHookOptions,
} from './useProgressSubscription';

export function useDocumentProgress(
  knowledgeBaseId: number | undefined,
  documentId: number | undefined,
  options: ProgressHookOptions<DocumentRecord> = {},
) {
  const queryClient = useQueryClient();
  const enabled =
    options.enabled !== false && isPositiveId(knowledgeBaseId) && isPositiveId(documentId);
  const client = options.client ?? progressWebSocketClient;
  const target = useMemo(
    () => ({
      resource: 'DOCUMENT' as const,
      knowledgeBaseId: knowledgeBaseId ?? -1,
      documentId: documentId ?? -1,
    }),
    [knowledgeBaseId, documentId],
  );
  const query = useQuery({
    queryKey: queryKeys.documents.detail(knowledgeBaseId ?? -1, documentId ?? -1),
    queryFn: async ({ signal }) => {
      const sequence = client.getSnapshot(target).sequence;
      const data = await documentApi.get(knowledgeBaseId!, documentId!, signal);
      if (!signal.aborted) client.markSynchronized(target, sequence);
      return data;
    },
    initialData: options.initialData,
    enabled,
    staleTime: PROGRESS_POLL_INTERVAL_MS,
    refetchInterval: (current) =>
      current.state.data && isDocumentProcessing(current.state.data.status)
        ? PROGRESS_POLL_INTERVAL_MS
        : false,
  });
  const synchronize = useCallback(async () => {
    await Promise.all([
      queryClient.invalidateQueries(
        { queryKey: queryKeys.documents.detail(knowledgeBaseId!, documentId!), exact: true },
        { throwOnError: true },
      ),
      queryClient.invalidateQueries({ queryKey: queryKeys.documents.lists(knowledgeBaseId!) }),
    ]);
  }, [queryClient, knowledgeBaseId, documentId]);
  const progress = useProgressSubscription(
    target,
    enabled && Boolean(query.data) && isDocumentProcessing(query.data!.status),
    synchronize,
    client,
  );
  return { ...query, progress };
}
