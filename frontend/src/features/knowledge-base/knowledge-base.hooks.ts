import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { knowledgeBaseApi } from '../../api/knowledge-base.api';
import { queryKeys } from '../../api/query-keys';
import type { PaginationParams, UpsertKnowledgeBaseRequest } from '../../api/contracts';

export function useKnowledgeBaseList(params: PaginationParams) {
  return useQuery({
    queryKey: queryKeys.knowledgeBases.list(params),
    queryFn: () => knowledgeBaseApi.list(params),
  });
}

export function useKnowledgeBase(knowledgeBaseId: number | undefined) {
  return useQuery({
    queryKey: queryKeys.knowledgeBases.detail(knowledgeBaseId ?? -1),
    queryFn: () => {
      if (!knowledgeBaseId) {
        throw new Error('知识库 ID 不合法');
      }
      return knowledgeBaseApi.get(knowledgeBaseId);
    },
    enabled: knowledgeBaseId !== undefined,
  });
}

export function useCreateKnowledgeBase() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (input: UpsertKnowledgeBaseRequest) => knowledgeBaseApi.create(input),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: queryKeys.knowledgeBases.all }),
  });
}

export function useUpdateKnowledgeBase() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: ({
      knowledgeBaseId,
      input,
    }: {
      knowledgeBaseId: number;
      input: UpsertKnowledgeBaseRequest;
    }) => knowledgeBaseApi.update(knowledgeBaseId, input),
    onSuccess: (knowledgeBase) => {
      void queryClient.invalidateQueries({ queryKey: queryKeys.knowledgeBases.all });
      queryClient.setQueryData(queryKeys.knowledgeBases.detail(knowledgeBase.id), knowledgeBase);
    },
  });
}

export function useDeleteKnowledgeBase() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (knowledgeBaseId: number) => knowledgeBaseApi.delete(knowledgeBaseId),
    onSuccess: (_, knowledgeBaseId) => {
      void queryClient.invalidateQueries({ queryKey: queryKeys.knowledgeBases.all });
      queryClient.removeQueries({ queryKey: queryKeys.knowledgeBases.detail(knowledgeBaseId) });
      queryClient.removeQueries({ queryKey: queryKeys.documents.lists(knowledgeBaseId) });
    },
  });
}
