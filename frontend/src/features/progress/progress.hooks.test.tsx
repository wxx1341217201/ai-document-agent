import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { act, cleanup, renderHook } from '@testing-library/react';
import { StrictMode, type ReactNode } from 'react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type { DocumentRecord } from '../../api/contracts';
import { documentApi } from '../../api/document.api';
import { executionApi } from '../../api/execution.api';
import { queryKeys } from '../../api/query-keys';
import { completeMockExecution, mockExecution } from '../../test/mocks/execution-fixtures';
import {
  createProgressHarness,
  documentSubscription,
  executionSubscription,
  mockDocumentEvent,
  mockProgressEvent,
  progressAck,
} from '../../test/mocks/progress-socket';
import { useDocumentProgress } from './useDocumentProgress';
import { useExecutionProgress } from './useExecutionProgress';

const resources: ReturnType<typeof setup>[] = [];
function setup(strict = false) {
  const transport = createProgressHarness();
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  const wrapper = ({ children }: { children: ReactNode }) =>
    strict ? (
      <StrictMode>
        <QueryClientProvider client={queryClient}>{children}</QueryClientProvider>
      </StrictMode>
    ) : (
      <QueryClientProvider client={queryClient}>{children}</QueryClientProvider>
    );
  const execution = {
    ...mockExecution({
      knowledgeBaseId: 1,
      conversationId: 301,
      agentMode: 'REACT',
      question: '问题',
    }),
    executionId: executionSubscription.executionId,
  };
  const document: DocumentRecord = {
    id: 101,
    knowledgeBaseId: 1,
    originalName: 'progress.pdf',
    contentType: 'application/pdf',
    extension: 'pdf',
    sizeBytes: 100,
    sha256: 'mock-sha256',
    status: 'PROCESSING',
    errorCode: null,
    errorMessage: null,
    createdAt: '2026-10-11T08:30:00',
    updatedAt: '2026-10-11T08:30:00',
  };
  return { ...transport, queryClient, wrapper, execution, document };
}
function resource(strict = false) {
  const result = setup(strict);
  resources.push(result);
  return result;
}
async function flush() {
  await act(async () => {
    await vi.advanceTimersByTimeAsync(1);
  });
}
async function advance(ms: number) {
  await act(async () => {
    await vi.advanceTimersByTimeAsync(ms);
    await vi.advanceTimersByTimeAsync(1);
  });
}

beforeEach(() => vi.useFakeTimers());
afterEach(() => {
  cleanup();
  for (const item of resources.splice(0)) {
    item.client.dispose();
    item.queryClient.clear();
  }
  vi.restoreAllMocks();
  vi.useRealTimers();
});

describe('progress hooks (Mock transport / REST service)', () => {
  it('keeps REST authoritative, refetches gaps/terminal events, and invalidates related queries', async () => {
    const { client, sockets, wrapper, execution, queryClient } = resource();
    const get = vi.spyOn(executionApi, 'get').mockResolvedValue(execution);
    queryClient.setQueryData(queryKeys.executions.list({ knowledgeBaseId: 1 }), []);
    queryClient.setQueryData(queryKeys.messages.list(301), []);
    queryClient.setQueryData(queryKeys.conversations.list(1), []);
    const { result } = renderHook(
      () => useExecutionProgress(1, execution.executionId, { client, initialData: execution }),
      { wrapper },
    );
    act(() => {
      sockets[0].open();
      sockets[0].message(progressAck(executionSubscription));
    });
    await flush();
    expect(get).toHaveBeenCalledExactlyOnceWith(execution.executionId, 1, expect.any(AbortSignal));
    const first = mockProgressEvent(1);
    act(() => {
      sockets[0].message(first);
      sockets[0].message(first);
      sockets[0].message(mockProgressEvent(1));
    });
    await flush();
    expect(result.current.data?.status).toBe('PENDING');
    expect(result.current.progress.event?.payload.summary).toBe('执行已开始');
    expect(get).toHaveBeenCalledTimes(1);
    act(() => {
      sockets[0].message(mockProgressEvent(3));
      sockets[0].message(mockProgressEvent(2));
    });
    await flush();
    expect(get).toHaveBeenCalledTimes(2);
    expect(result.current.progress.sequence).toBe(3);
    expect(result.current.progress.gap).toBeNull();
    get.mockResolvedValue(completeMockExecution(execution));
    const terminal = mockProgressEvent(4, { type: 'EXECUTION_COMPLETED' });
    act(() => {
      sockets[0].message(terminal);
      sockets[0].message(terminal);
    });
    await flush();
    expect(get).toHaveBeenCalledTimes(3);
    expect(result.current.data?.status).toBe('SUCCEEDED');
    expect(sockets[0].closed).toBe(true);
    expect(queryClient.getQueryState(queryKeys.messages.list(301))?.isInvalidated).toBe(true);
    expect(queryClient.getQueryState(queryKeys.conversations.list(1))?.isInvalidated).toBe(true);
    expect(
      queryClient.getQueryState(queryKeys.executions.list({ knowledgeBaseId: 1 }))?.isInvalidated,
    ).toBe(true);
    await advance(30_000);
    expect(get).toHaveBeenCalledTimes(3);
    expect(sockets).toHaveLength(1);
  });

  it('refetches after reconnect even when there are no replayed events', async () => {
    const { client, sockets, wrapper, execution } = resource();
    const get = vi.spyOn(executionApi, 'get').mockResolvedValue(execution);
    const { result } = renderHook(
      () => useExecutionProgress(1, execution.executionId, { client, initialData: execution }),
      { wrapper },
    );
    act(() => {
      sockets[0].open();
      sockets[0].message(progressAck(executionSubscription, 5));
    });
    await flush();
    act(() => sockets[0].disconnect());
    expect(result.current.progress.connection).toBe('reconnecting');
    await advance(1_000);
    get.mockResolvedValue(completeMockExecution(execution));
    act(() => {
      sockets[1].open();
      sockets[1].message(progressAck(executionSubscription, 20));
    });
    await flush();
    expect(get).toHaveBeenCalledTimes(2);
    expect(result.current.data?.status).toBe('SUCCEEDED');
    expect(sockets[1].closed).toBe(true);
  });

  it('cancels an older REST read when a terminal event arrives and ignores its late result', async () => {
    const { client, sockets, wrapper, execution } = resource();
    let release!: (value: typeof execution) => void;
    let previousSignal: AbortSignal | undefined;
    const previous = new Promise<typeof execution>((resolve) => {
      release = resolve;
    });
    const get = vi
      .spyOn(executionApi, 'get')
      .mockImplementationOnce((_executionId, _knowledgeBaseId, signal) => {
        previousSignal = signal;
        return previous;
      })
      .mockResolvedValue(completeMockExecution(execution));
    const { result } = renderHook(
      () => useExecutionProgress(1, execution.executionId, { client, initialData: execution }),
      { wrapper },
    );
    act(() => {
      sockets[0].open();
      sockets[0].message(progressAck(executionSubscription));
    });
    await flush();
    expect(result.current.isFetching).toBe(true);
    act(() => sockets[0].message(mockProgressEvent(1, { type: 'EXECUTION_COMPLETED' })));
    await flush();
    expect(previousSignal?.aborted).toBe(true);
    expect(result.current.data?.status).toBe('SUCCEEDED');
    await act(async () => release(execution));
    await flush();
    expect(result.current.data?.status).toBe('SUCCEEDED');
    expect(get).toHaveBeenCalledTimes(2);
  });

  it('falls back to REST polling after a subscription rejection', async () => {
    const { client, sockets, wrapper, execution } = resource();
    const get = vi.spyOn(executionApi, 'get').mockResolvedValue(completeMockExecution(execution));
    const { result } = renderHook(
      () => useExecutionProgress(1, execution.executionId, { client, initialData: execution }),
      { wrapper },
    );
    act(() => {
      sockets[0].open();
      sockets[0].message({ type: 'SUBSCRIPTION_REJECTED', code: 'FORBIDDEN' });
    });
    expect(result.current.progress.connection).toBe('unavailable');
    expect(get).not.toHaveBeenCalled();
    await advance(5_000);
    expect(get).toHaveBeenCalledTimes(1);
    expect(result.current.data?.status).toBe('SUCCEEDED');
    expect(sockets[0].closed).toBe(true);
  });

  it('waits for document REST to confirm a terminal event when the transaction is not yet visible', async () => {
    const { client, sockets, wrapper, document, queryClient } = resource();
    const get = vi.spyOn(documentApi, 'get').mockResolvedValue(document);
    const listKey = queryKeys.documents.list(1, { page: 0, size: 20 });
    queryClient.setQueryData(listKey, { content: [document] });
    const { result } = renderHook(
      () => useDocumentProgress(1, 101, { client, initialData: document }),
      { wrapper },
    );
    act(() => {
      sockets[0].open();
      sockets[0].message(progressAck(documentSubscription));
    });
    await flush();
    expect(get).toHaveBeenCalledExactlyOnceWith(1, 101, expect.any(AbortSignal));
    const terminal = mockDocumentEvent(1, { type: 'DOCUMENT_READY' });
    act(() => {
      sockets[0].message(terminal);
      sockets[0].message(terminal);
    });
    await flush();
    expect(get).toHaveBeenCalledTimes(2);
    expect(result.current.data?.status).toBe('PROCESSING');
    expect(sockets[0].closed).toBe(false);
    expect(queryClient.getQueryState(listKey)?.isInvalidated).toBe(true);
    get.mockResolvedValue({ ...document, status: 'READY' });
    await advance(5_000);
    expect(get).toHaveBeenCalledTimes(3);
    expect(result.current.data?.status).toBe('READY');
    expect(sockets[0].closed).toBe(true);
  });

  it('preserves a gap and cached status after REST fails, then recovers through polling', async () => {
    const { client, sockets, wrapper, document } = resource();
    const get = vi.spyOn(documentApi, 'get').mockResolvedValue(document);
    const { result } = renderHook(
      () => useDocumentProgress(1, 101, { client, initialData: document }),
      { wrapper },
    );
    act(() => {
      sockets[0].open();
      sockets[0].message(progressAck(documentSubscription, 2));
    });
    await flush();
    get.mockRejectedValueOnce(new Error('REST unavailable'));
    act(() => sockets[0].message(mockDocumentEvent(4)));
    await flush();
    expect(result.current.isError).toBe(true);
    expect(result.current.data?.status).toBe('PROCESSING');
    expect(result.current.progress.gap).toEqual({ expected: 3, received: 4 });
    await advance(5_000);
    expect(result.current.isSuccess).toBe(true);
    expect(result.current.progress.gap).toBeNull();
    expect(get).toHaveBeenCalledTimes(3);
  });

  it('cleans up on resource switch/unmount and does not apply events from the previous resource', async () => {
    const { client, sockets, wrapper, document } = resource();
    vi.spyOn(documentApi, 'get').mockResolvedValue(document);
    const { result, rerender, unmount } = renderHook(
      ({ id }) => useDocumentProgress(1, id, { client, initialData: { ...document, id } }),
      { wrapper, initialProps: { id: 101 } },
    );
    act(() => {
      sockets[0].open();
      sockets[0].message(progressAck(documentSubscription));
      sockets[0].message(mockDocumentEvent(1));
    });
    await flush();
    expect(result.current.progress.event?.payload.documentId).toBe(101);
    rerender({ id: 103 });
    expect(sockets[0].closed).toBe(true);
    expect(result.current.data?.id).toBe(103);
    expect(result.current.progress.event).toBeNull();
    act(() => sockets[0].message(mockDocumentEvent(20)));
    expect(result.current.progress.event).toBeNull();
    unmount();
    expect(sockets[1].closed).toBe(true);
    await advance(30_000);
    expect(sockets).toHaveLength(2);
  });

  it('does not connect or fetch disabled/invalid resources, and does not subscribe terminal resources', async () => {
    const { client, sockets, wrapper, document, execution } = resource();
    const executionGet = vi.spyOn(executionApi, 'get');
    const documentGet = vi.spyOn(documentApi, 'get');
    renderHook(
      () => {
        useExecutionProgress(undefined, 'invalid', { client });
        useDocumentProgress(0, 101, { client });
        useExecutionProgress(1, '32345678-1234-1234-1234-123456789abc', { client, enabled: false });
        useDocumentProgress(1, 101, { client, initialData: { ...document, status: 'READY' } });
        useExecutionProgress(1, execution.executionId, {
          client,
          initialData: completeMockExecution(execution),
        });
      },
      { wrapper },
    );
    await flush();
    expect(executionGet).not.toHaveBeenCalled();
    expect(documentGet).not.toHaveBeenCalled();
    expect(sockets).toHaveLength(0);
  });

  it('survives StrictMode effect cleanup and keeps one live subscription', async () => {
    const { client, sockets, wrapper, execution } = resource(true);
    const get = vi.spyOn(executionApi, 'get').mockResolvedValue(execution);
    const { result, unmount } = renderHook(
      () => useExecutionProgress(1, execution.executionId, { client, initialData: execution }),
      { wrapper, reactStrictMode: true },
    );
    expect(sockets).toHaveLength(2);
    expect(sockets[0].closed).toBe(true);
    act(() => {
      sockets[1].open();
      sockets[1].message(progressAck(executionSubscription));
    });
    await flush();
    expect(result.current.progress.connection).toBe('subscribed');
    expect(get).toHaveBeenCalledTimes(1);
    unmount();
    expect(sockets[1].closed).toBe(true);
  });
});
