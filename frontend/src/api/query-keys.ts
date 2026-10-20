import type { ExecutionListParams, PaginationParams } from './contracts';

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
    chunks: (knowledgeBaseId: number, documentId: number, params: PaginationParams) =>
      ['document-chunks', knowledgeBaseId, documentId, params] as const,
    context: (knowledgeBaseId: number, documentId: number, chunkId: number) =>
      ['citation-context', knowledgeBaseId, documentId, chunkId] as const,
    file: (knowledgeBaseId: number, documentId: number) =>
      ['document-file', knowledgeBaseId, documentId] as const,
  },
  conversations: {
    list: (knowledgeBaseId: number) => ['conversations', knowledgeBaseId] as const,
  },
  messages: {
    list: (conversationId: number) => ['messages', conversationId] as const,
    sends: () => ['messages', 'send'] as const,
    send: (conversationId: number) => [...queryKeys.messages.sends(), conversationId] as const,
  },
  executions: {
    all: ['executions'] as const,
    list: (params?: ExecutionListParams | Record<string, string | number | undefined>) =>
      ['executions', params] as const,
    detail: (knowledgeBaseId: number, executionId: string) =>
      ['execution', knowledgeBaseId, executionId] as const,
  },
  dashboard: { recent: ['dashboard', 'recent'] as const },
  system: { health: ['system', 'health'] as const },
} as const;
