import { ApiClientError, apiRequest } from './client';
import type {
  RecentDocument,
  RecentKnowledgeBase,
  PageResponse,
  PaginationParams,
} from './contracts';
import { executionApi } from './execution.api';

/** The advanced execution summary still uses the existing execution contract. */
async function allPages<T>(
  request: (params: PaginationParams) => Promise<PageResponse<T>>,
  signal?: AbortSignal,
) {
  const items: T[] = [];
  let totalPages = 1;
  for (let page = 0; page < totalPages; page += 1) {
    signal?.throwIfAborted();
    const result = await request({ page, size: 20 });
    if (
      !result ||
      !Array.isArray(result.content) ||
      result.page !== page ||
      !Number.isSafeInteger(result.totalPages) ||
      result.totalPages < 0
    ) {
      throw new ApiClientError({
        code: 'INVALID_API_RESPONSE',
        message: '服务端分页数据不符合契约。',
      });
    }
    items.push(...result.content);
    totalPages = result.totalPages;
  }
  return items;
}

function recent<T extends { updatedAt: string }>(items: T[]) {
  const time = (value: string) => (Number.isFinite(Date.parse(value)) ? Date.parse(value) : 0);
  return [...items].sort((left, right) => time(right.updatedAt) - time(left.updatedAt)).slice(0, 5);
}

export const dashboardApi = {
  knowledgeBases(params: PaginationParams, signal?: AbortSignal) {
    return apiRequest<PageResponse<RecentKnowledgeBase>>({
      method: 'GET',
      url: '/dashboard/knowledge-bases',
      params,
      signal,
    });
  },
  documents(params: PaginationParams, signal?: AbortSignal) {
    return apiRequest<PageResponse<RecentDocument>>({
      method: 'GET',
      url: '/dashboard/documents',
      params,
      signal,
    });
  },
  async executions(signal?: AbortSignal) {
    return recent(await allPages((params) => executionApi.list(params, signal), signal));
  },
};
