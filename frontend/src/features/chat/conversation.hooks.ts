import { useInfiniteQuery, useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { conversationApi } from '../../api/conversation.api';
import type { AgentSubmitResult } from '../../api/agent-gateway';
import type { CreateConversationRequest } from '../../api/contracts';
import { queryKeys } from '../../api/query-keys';

export function useConversationList(knowledgeBaseId: number) {
  return useInfiniteQuery({
    queryKey: queryKeys.conversations.list(knowledgeBaseId),
    initialPageParam: 0,
    queryFn: ({ pageParam, signal }) =>
      conversationApi.list(knowledgeBaseId, { page: pageParam, size: 20 }, signal),
    getNextPageParam: (lastPage) =>
      lastPage.page + 1 < lastPage.totalPages ? lastPage.page + 1 : undefined,
  });
}

export function useCreateConversation(knowledgeBaseId: number) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (input: CreateConversationRequest) =>
      conversationApi.create(knowledgeBaseId, input),
    onSuccess: () =>
      queryClient.invalidateQueries({ queryKey: queryKeys.conversations.list(knowledgeBaseId) }),
  });
}

export function useConversationMessages(conversationId: number) {
  return useQuery({
    queryKey: queryKeys.messages.list(conversationId),
    queryFn: ({ signal }) => conversationApi.history(conversationId, signal),
  });
}

export function useDeleteConversation(knowledgeBaseId: number) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (conversationId: number) => conversationApi.delete(conversationId),
    onSuccess: (_, conversationId) => {
      queryClient.removeQueries({ queryKey: queryKeys.messages.list(conversationId) });
      const cache = queryClient.getMutationCache();
      cache
        .findAll({ mutationKey: queryKeys.messages.send(conversationId), exact: true })
        .forEach((mutation) => {
          const result = mutation.state.data as AgentSubmitResult | undefined;
          if (result?.kind === 'execution') {
            queryClient.removeQueries({
              queryKey: queryKeys.executions.detail(knowledgeBaseId, result.executionId),
            });
          }
          cache.remove(mutation);
        });
      void queryClient.invalidateQueries({
        queryKey: queryKeys.conversations.list(knowledgeBaseId),
      });
    },
  });
}
