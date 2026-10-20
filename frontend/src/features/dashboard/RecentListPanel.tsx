import type { InfiniteData, UseInfiniteQueryResult } from '@tanstack/react-query';
import { Button, Spin } from 'antd';
import type { ReactNode } from 'react';
import type { PageResponse } from '../../api/contracts';
import { PageLoading, RequestErrorAlert, RetryButton } from '../../components/feedback';

export function RecentListPanel<T>({
  label,
  query,
  children,
}: {
  label: string;
  query: UseInfiniteQueryResult<InfiniteData<PageResponse<T>>, Error>;
  children: ReactNode;
}) {
  const loadNext = () => {
    if (query.hasNextPage && !query.isFetching) {
      void query.fetchNextPage({ cancelRefetch: false });
    }
  };
  const loadNearBottom = (element: HTMLDivElement) => {
    if (
      !query.isFetchNextPageError &&
      element.scrollHeight - element.scrollTop - element.clientHeight <= 48
    ) {
      loadNext();
    }
  };
  const loaded = query.data?.pages.reduce((total, page) => total + page.content.length, 0) ?? 0;
  const total = query.data?.pages[0]?.totalElements ?? 0;

  return (
    <div
      className="dashboard-list-scroll"
      role="region"
      aria-label={`最近${label}列表`}
      tabIndex={0}
      onScroll={(event) => loadNearBottom(event.currentTarget)}
      onWheel={(event) => {
        if (event.deltaY > 0) loadNearBottom(event.currentTarget);
      }}
    >
      {query.isLoading ? <PageLoading label={`正在加载${label}…`} /> : null}
      {query.isError && !query.data ? (
        <div className="dashboard-list-error">
          <RequestErrorAlert error={query.error} />
          <RetryButton loading={query.isFetching} onClick={() => void query.refetch()} />
        </div>
      ) : null}
      {query.data ? children : null}
      {query.data && loaded > 0 ? (
        <div className="dashboard-list-footer" aria-live="polite">
          {query.isFetchingNextPage ? (
            <span role="status">
              <Spin size="small" /> 正在加载更多{label}…
            </span>
          ) : query.isFetchNextPageError ? (
            <>
              <span>更多{label}加载失败</span>
              <RetryButton onClick={loadNext} />
            </>
          ) : query.hasNextPage ? (
            <Button type="text" onClick={loadNext}>
              加载更多{label}
            </Button>
          ) : (
            <span>
              已显示全部{total}条{label}
            </span>
          )}
        </div>
      ) : null}
    </div>
  );
}
