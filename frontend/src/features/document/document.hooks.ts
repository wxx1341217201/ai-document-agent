import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { documentApi } from '../../api/document.api';
import { queryKeys } from '../../api/query-keys';
import type { PaginationParams } from '../../api/contracts';
import { hasInFlightDocuments, isDocumentProcessing } from '../../utils/document-status';

const POLL_INTERVAL_MS = 5_000;

export function useDocumentList(knowledgeBaseId: number | undefined, params: PaginationParams) {
  return useQuery({
    queryKey: queryKeys.documents.list(knowledgeBaseId ?? -1, params),
    queryFn: () => {
      if (!knowledgeBaseId) {
        throw new Error('知识库 ID 不合法');
      }
      return documentApi.list(knowledgeBaseId, params);
    },
    enabled: knowledgeBaseId !== undefined,
    refetchInterval: (query) => {
      const documents = query.state.data?.content ?? [];
      return hasInFlightDocuments(documents.map((document) => document.status))
        ? POLL_INTERVAL_MS
        : false;
    },
  });
}

export function useDocument(knowledgeBaseId: number | undefined, documentId: number | undefined) {
  return useQuery({
    queryKey: queryKeys.documents.detail(knowledgeBaseId ?? -1, documentId ?? -1),
    queryFn: () => {
      if (!knowledgeBaseId || !documentId) {
        throw new Error('文档地址不合法');
      }
      return documentApi.get(knowledgeBaseId, documentId);
    },
    enabled: knowledgeBaseId !== undefined && documentId !== undefined,
    refetchInterval: (query) =>
      query.state.data && isDocumentProcessing(query.state.data.status) ? POLL_INTERVAL_MS : false,
  });
}

export function useUploadDocument(knowledgeBaseId: number) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: ({ file, onProgress }: { file: File; onProgress?: (percent: number) => void }) =>
      documentApi.upload(knowledgeBaseId, file, onProgress),
    onSuccess: () =>
      queryClient.invalidateQueries({ queryKey: queryKeys.documents.lists(knowledgeBaseId) }),
  });
}

export function useRetryDocument(knowledgeBaseId: number) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (documentId: number) => documentApi.retry(knowledgeBaseId, documentId),
    onSuccess: (_, documentId) => {
      void queryClient.invalidateQueries({ queryKey: queryKeys.documents.lists(knowledgeBaseId) });
      void queryClient.invalidateQueries({
        queryKey: queryKeys.documents.detail(knowledgeBaseId, documentId),
      });
    },
  });
}

export function useDeleteDocument(knowledgeBaseId: number) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (documentId: number) => documentApi.delete(knowledgeBaseId, documentId),
    onSuccess: (_, documentId) => {
      void queryClient.invalidateQueries({ queryKey: queryKeys.documents.lists(knowledgeBaseId) });
      queryClient.removeQueries({
        queryKey: queryKeys.documents.detail(knowledgeBaseId, documentId),
      });
    },
  });
}
