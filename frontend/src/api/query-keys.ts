import type { PaginationParams } from './contracts';

export const queryKeys = {
  knowledgeBases: {
    all: ['knowledge-bases'] as const,
    lists: () => [...queryKeys.knowledgeBases.all, 'list'] as const,
    list: (params: PaginationParams) => [...queryKeys.knowledgeBases.lists(), params] as const,
    details: () => [...queryKeys.knowledgeBases.all, 'detail'] as const,
    detail: (knowledgeBaseId: number) =>
      [...queryKeys.knowledgeBases.details(), knowledgeBaseId] as const,
  },
  documents: {
    all: ['documents'] as const,
    lists: (knowledgeBaseId: number) =>
      [...queryKeys.documents.all, knowledgeBaseId, 'list'] as const,
    list: (knowledgeBaseId: number, params: PaginationParams) =>
      [...queryKeys.documents.lists(knowledgeBaseId), params] as const,
    details: (knowledgeBaseId: number) =>
      [...queryKeys.documents.all, knowledgeBaseId, 'detail'] as const,
    detail: (knowledgeBaseId: number, documentId: number) =>
      [...queryKeys.documents.details(knowledgeBaseId), documentId] as const,
  },
  conversations: {
    list: (knowledgeBaseId: number) => ['conversations', knowledgeBaseId] as const,
  },
  messages: {
    list: (conversationId: string) => ['messages', conversationId] as const,
  },
  executions: {
    list: (params?: Record<string, string | number | undefined>) => ['executions', params] as const,
    detail: (executionId: string) => ['execution', executionId] as const,
  },
} as const;
