import { apiRequest } from './client';
import type {
  Conversation,
  ConversationMessage,
  CreateConversationRequest,
  PageResponse,
  PaginationParams,
  SendConversationMessageRequest,
  SendConversationMessageResponse,
} from './contracts';

export const conversationApi = {
  list(knowledgeBaseId: number, params: PaginationParams, signal?: AbortSignal) {
    return apiRequest<PageResponse<Conversation>>({
      method: 'GET',
      url: `/knowledge-bases/${knowledgeBaseId}/conversations`,
      params,
      signal,
    });
  },

  create(knowledgeBaseId: number, input: CreateConversationRequest) {
    return apiRequest<Conversation>({
      method: 'POST',
      url: `/knowledge-bases/${knowledgeBaseId}/conversations`,
      data: { title: input.title?.trim() || undefined, agentMode: input.agentMode },
    });
  },

  listMessages(conversationId: number, params: PaginationParams, signal?: AbortSignal) {
    return apiRequest<PageResponse<ConversationMessage>>({
      method: 'GET',
      url: `/conversations/${conversationId}/messages`,
      params,
      signal,
    });
  },

  /** The backend pages oldest first: read every page so reopening also restores recent turns. */
  async history(conversationId: number, signal?: AbortSignal): Promise<ConversationMessage[]> {
    const messages = new Map<number, ConversationMessage>();
    let page = 0;
    let totalPages = 1;
    while (page < totalPages) {
      const result = await conversationApi.listMessages(
        conversationId,
        { page, size: 100 },
        signal,
      );
      result.content.forEach((message) => messages.set(message.id, message));
      totalPages = result.totalPages;
      page += 1;
    }
    return [...messages.values()].sort(
      (left, right) => left.sequenceNo - right.sequenceNo || left.id - right.id,
    );
  },

  sendMessage(conversationId: number, input: SendConversationMessageRequest) {
    return apiRequest<SendConversationMessageResponse>({
      method: 'POST',
      url: `/conversations/${conversationId}/messages`,
      data: { content: input.content.trim() },
      // Agent modes have a 40s default backend deadline; the shared 15s timeout is too short.
      timeout: 60_000,
    });
  },

  delete(conversationId: number) {
    return apiRequest<void>({ method: 'DELETE', url: `/conversations/${conversationId}` });
  },
};
