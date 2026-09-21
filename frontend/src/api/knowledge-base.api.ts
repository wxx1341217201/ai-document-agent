import { apiRequest } from './client';
import type {
  KnowledgeBase,
  PageResponse,
  PaginationParams,
  UpsertKnowledgeBaseRequest,
} from './contracts';

const resourcePath = '/knowledge-bases';

export const knowledgeBaseApi = {
  list(params: PaginationParams) {
    return apiRequest<PageResponse<KnowledgeBase>>({
      method: 'GET',
      url: resourcePath,
      params,
    });
  },

  get(knowledgeBaseId: number) {
    return apiRequest<KnowledgeBase>({
      method: 'GET',
      url: `${resourcePath}/${knowledgeBaseId}`,
    });
  },

  create(input: UpsertKnowledgeBaseRequest) {
    return apiRequest<KnowledgeBase>({
      method: 'POST',
      url: resourcePath,
      data: normalizeInput(input),
    });
  },

  update(knowledgeBaseId: number, input: UpsertKnowledgeBaseRequest) {
    return apiRequest<KnowledgeBase>({
      method: 'PUT',
      url: `${resourcePath}/${knowledgeBaseId}`,
      data: normalizeInput(input),
    });
  },

  delete(knowledgeBaseId: number) {
    return apiRequest<void>({
      method: 'DELETE',
      url: `${resourcePath}/${knowledgeBaseId}`,
    });
  },
};

function normalizeInput(input: UpsertKnowledgeBaseRequest): UpsertKnowledgeBaseRequest {
  return {
    name: input.name.trim(),
    description: input.description?.trim() || undefined,
  };
}
