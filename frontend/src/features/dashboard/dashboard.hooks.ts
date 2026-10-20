import { useInfiniteQuery } from '@tanstack/react-query';
import { dashboardApi } from '../../api/dashboard.api';
import { queryKeys } from '../../api/query-keys';

export function useDashboard() {
  const knowledgeBases = useInfiniteQuery({
    queryKey: [...queryKeys.knowledgeBases.all, 'recent', 'pages'],
    initialPageParam: 0,
    queryFn: ({ pageParam, signal }) =>
      dashboardApi.knowledgeBases({ page: pageParam, size: 5 }, signal),
    getNextPageParam: (lastPage) =>
      lastPage.page + 1 < lastPage.totalPages ? lastPage.page + 1 : undefined,
    staleTime: 30_000,
  });
  const documents = useInfiniteQuery({
    queryKey: [...queryKeys.dashboard.recent, 'documents', 'pages'],
    initialPageParam: 0,
    queryFn: ({ pageParam, signal }) =>
      dashboardApi.documents({ page: pageParam, size: 5 }, signal),
    getNextPageParam: (lastPage) =>
      lastPage.page + 1 < lastPage.totalPages ? lastPage.page + 1 : undefined,
    staleTime: 30_000,
  });
  return { knowledgeBases, documents };
}
