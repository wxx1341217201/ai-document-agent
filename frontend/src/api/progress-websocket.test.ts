import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import {
  createProgressHarness,
  documentSubscription,
  executionSubscription,
  ingestionId,
  mockDocumentEvent,
  mockProgressEvent,
  progressAck,
} from '../test/mocks/progress-socket';
import type { ProgressObserver } from './progress.contracts';
import { ProgressWebSocketClient, progressWebSocketUrl } from './progress-websocket';

const clients: ProgressWebSocketClient[] = [];
function harness(options: ConstructorParameters<typeof ProgressWebSocketClient>[0] = {}) {
  const result = createProgressHarness(options);
  clients.push(result.client);
  return result;
}
function observer(): ProgressObserver & {
  onChange: ReturnType<typeof vi.fn>;
  onResync: ReturnType<typeof vi.fn>;
} {
  return { onChange: vi.fn(), onResync: vi.fn() };
}

beforeEach(() => vi.useFakeTimers());
afterEach(() => {
  for (const client of clients.splice(0)) client.dispose();
  vi.useRealTimers();
});

describe('ProgressWebSocketClient (transport Mock)', () => {
  it('lazily shares a connection and reference-counts SUBSCRIBE / UNSUBSCRIBE', () => {
    const { client, sockets } = harness();
    expect(sockets).toHaveLength(0);
    const first = observer();
    const second = observer();
    const stopFirst = client.subscribe(executionSubscription, first);
    const stopSecond = client.subscribe(executionSubscription, second);
    const stopDocument = client.subscribe(documentSubscription, observer());
    expect(sockets).toHaveLength(1);
    const socket = sockets[0];
    socket.open();
    expect(socket.sent).toEqual([{ action: 'SUBSCRIBE', ...executionSubscription }]);
    socket.message(progressAck(executionSubscription));
    expect(socket.sent[1]).toEqual({ action: 'SUBSCRIBE', ...documentSubscription });
    socket.message(progressAck(documentSubscription));
    stopFirst();
    expect(socket.sent).toHaveLength(2);
    stopSecond();
    expect(socket.sent[2]).toEqual({ action: 'UNSUBSCRIBE', ...executionSubscription });
    socket.message({ type: 'UNSUBSCRIBED' });
    expect(socket.closed).toBe(false);
    stopDocument();
    expect(socket.closed).toBe(true);
    expect(vi.getTimerCount()).toBe(0);
    stopDocument();
  });

  it('reconnects, resubscribes and requests authoritative REST state without replay', () => {
    const { client, sockets } = harness();
    const listener = observer();
    client.subscribe(executionSubscription, listener);
    sockets[0].open();
    sockets[0].message(progressAck(executionSubscription, 5));
    sockets[0].message(mockProgressEvent(6));
    const staleMessage = sockets[0].onmessage;
    sockets[0].error();
    sockets[0].disconnect();
    expect(listener.onChange.mock.lastCall?.[0].connection).toBe('reconnecting');
    vi.advanceTimersByTime(999);
    expect(sockets).toHaveLength(1);
    vi.advanceTimersByTime(1);
    expect(sockets).toHaveLength(2);
    sockets[1].open();
    sockets[1].message(progressAck(executionSubscription, 10));
    expect(listener.onResync.mock.calls.map(([reason]) => reason)).toEqual([
      'subscribe',
      'reconnect',
    ]);
    expect(sockets[1].sent).toEqual([{ action: 'SUBSCRIBE', ...executionSubscription }]);
    staleMessage?.call(
      sockets[0] as unknown as WebSocket,
      new MessageEvent('message', { data: JSON.stringify(mockProgressEvent(99)) }),
    );
    sockets[1].message(mockProgressEvent(6));
    sockets[1].message(mockProgressEvent(11));
    expect(listener.onChange.mock.lastCall?.[0].sequence).toBe(11);
    expect(listener.onResync).toHaveBeenCalledTimes(2);
  });

  it('uses bounded exponential backoff and resets it only after SUBSCRIBED', () => {
    const { client, sockets } = harness();
    client.subscribe(executionSubscription, observer());
    for (const delay of [1_000, 2_000, 4_000, 8_000, 15_000, 30_000, 30_000]) {
      const count = sockets.length;
      sockets.at(-1)!.open();
      sockets.at(-1)!.disconnect();
      vi.advanceTimersByTime(delay - 1);
      expect(sockets).toHaveLength(count);
      vi.advanceTimersByTime(1);
      expect(sockets).toHaveLength(count + 1);
    }
    sockets.at(-1)!.open();
    sockets.at(-1)!.message(progressAck(executionSubscription));
    sockets.at(-1)!.disconnect();
    vi.advanceTimersByTime(1_000);
    expect(sockets).toHaveLength(9);
  });

  it('adds configurable jitter while respecting the maximum delay', () => {
    const { client, sockets } = harness({
      random: () => 1,
      jitterRatio: 0.2,
      maxReconnectDelayMs: 1_100,
    });
    client.subscribe(executionSubscription, observer());
    sockets[0].disconnect();
    vi.advanceTimersByTime(1_099);
    expect(sockets).toHaveLength(1);
    vi.advanceTimersByTime(1);
    expect(sockets).toHaveLength(2);
  });

  it('ignores duplicates and out-of-order events, detects a gap, and never rolls back', () => {
    const { client, sockets } = harness();
    const listener = observer();
    client.subscribe(executionSubscription, listener);
    const socket = sockets[0];
    socket.open();
    socket.message(progressAck(executionSubscription));
    listener.onResync.mockClear();
    const first = mockProgressEvent(1);
    socket.message(first);
    socket.message(first);
    socket.message(mockProgressEvent(1));
    socket.message(mockProgressEvent(2));
    socket.message(mockProgressEvent(1));
    socket.message({ ...first, sequence: 3 });
    socket.message(mockProgressEvent(4));
    socket.message(mockProgressEvent(3));
    expect(listener.onChange.mock.lastCall?.[0]).toMatchObject({
      sequence: 4,
      gap: { expected: 3, received: 4 },
    });
    expect(listener.onResync).toHaveBeenCalledTimes(1);
    expect(listener.onResync.mock.lastCall?.[0]).toBe('gap');
    client.markSynchronized(executionSubscription, 2);
    expect(listener.onChange.mock.lastCall?.[0].gap).not.toBeNull();
    client.markSynchronized(executionSubscription, 4);
    expect(listener.onChange.mock.lastCall?.[0].gap).toBeNull();
  });

  it('accepts progress before SUBSCRIBED without lowering the sequence baseline', () => {
    const { client, sockets } = harness();
    const listener = observer();
    client.subscribe(executionSubscription, listener);
    sockets[0].open();
    sockets[0].message(mockProgressEvent(5));
    sockets[0].message(progressAck(executionSubscription, 3));
    expect(listener.onChange.mock.lastCall?.[0]).toMatchObject({
      sequence: 5,
      event: { sequence: 5 },
    });
    expect(listener.onResync.mock.calls.map(([reason]) => reason)).toEqual(['gap', 'subscribe']);
    sockets[0].message(mockProgressEvent(6));
    expect(listener.onResync).toHaveBeenCalledTimes(2);
  });

  it.each([
    'EXECUTION_COMPLETED',
    'EXECUTION_FAILED',
    'DOCUMENT_READY',
    'DOCUMENT_FAILED',
  ] as const)('requests one terminal refetch for %s even when it also reveals a gap', (type) => {
    const { client, sockets } = harness();
    const listener = observer();
    const document = type.startsWith('DOCUMENT');
    const target = document ? documentSubscription : executionSubscription;
    client.subscribe(target, listener);
    sockets[0].open();
    sockets[0].message(progressAck(target));
    listener.onResync.mockClear();
    const event = document ? mockDocumentEvent(3, { type }) : mockProgressEvent(3, { type });
    sockets[0].message(event);
    sockets[0].message(event);
    expect(listener.onResync).toHaveBeenCalledTimes(1);
    expect(listener.onResync.mock.lastCall?.[0]).toBe('terminal');
    expect(sockets[0].closed).toBe(false); // REST must confirm the terminal state first.
  });

  it('rebases document sequences for a new ingestion job and ignores late events from the old job', () => {
    const { client, sockets } = harness();
    const listener = observer();
    client.subscribe(documentSubscription, listener);
    sockets[0].open();
    sockets[0].message(progressAck(documentSubscription, 20));
    sockets[0].disconnect();
    vi.advanceTimersByTime(1_000);
    sockets[1].open();
    const nextJob = crypto.randomUUID();
    sockets[1].message(mockDocumentEvent(1, { executionId: nextJob }));
    sockets[1].message(progressAck(documentSubscription, 1, nextJob));
    sockets[1].message(mockDocumentEvent(21, { executionId: ingestionId }));
    sockets[1].message(mockDocumentEvent(2, { executionId: nextJob }));
    expect(listener.onChange.mock.lastCall?.[0]).toMatchObject({
      sequence: 2,
      gap: null,
      event: { executionId: nextJob },
    });
  });

  it('serializes commands because SUBSCRIPTION_REJECTED has no resource identity', () => {
    const { client, sockets } = harness();
    const executionListener = observer();
    const documentListener = observer();
    client.subscribe(executionSubscription, executionListener);
    client.subscribe(documentSubscription, documentListener);
    sockets[0].open();
    sockets[0].message({ type: 'SUBSCRIPTION_REJECTED', code: 'FORBIDDEN' });
    sockets[0].message(progressAck(documentSubscription));
    expect(executionListener.onChange.mock.lastCall?.[0]).toMatchObject({
      connection: 'unavailable',
      rejectionCode: 'FORBIDDEN',
    });
    expect(documentListener.onChange.mock.lastCall?.[0].connection).toBe('subscribed');
    vi.advanceTimersByTime(60_000);
    expect(sockets).toHaveLength(1);
    expect(executionListener.onResync).not.toHaveBeenCalled();
    client.reconnect();
    expect(sockets).toHaveLength(2);
  });

  it('ignores malformed/foreign events and projects only the safe payload fields', () => {
    const { client, sockets } = harness();
    const listener = observer();
    client.subscribe(executionSubscription, listener);
    sockets[0].open();
    sockets[0].message(progressAck(executionSubscription));
    const changes = listener.onChange.mock.calls.length;
    for (const value of [
      null,
      [],
      {},
      mockProgressEvent(0),
      mockProgressEvent(1, { executionId: crypto.randomUUID() }),
      mockDocumentEvent(1),
    ])
      sockets[0].message(value);
    sockets[0].rawMessage('{invalid');
    sockets[0].rawMessage(new Blob());
    const event = mockProgressEvent(1);
    sockets[0].message({
      ...event,
      payload: { ...event.payload, summary: '<script>bad</script>' },
    });
    expect(listener.onChange).toHaveBeenCalledTimes(changes);
    sockets[0].message({
      ...event,
      payload: { ...event.payload, reasoning: 'hidden', toolArguments: { secret: true } },
    });
    expect(listener.onChange.mock.lastCall?.[0].event.payload).toEqual(event.payload);
  });

  it('times out an incomplete handshake and a missing subscription acknowledgment', () => {
    const { client, sockets } = harness({ timeoutMs: 100 });
    client.subscribe(executionSubscription, observer());
    vi.advanceTimersByTime(100);
    expect(sockets[0].closed).toBe(true);
    vi.advanceTimersByTime(1_000);
    sockets[1].open();
    vi.advanceTimersByTime(100);
    expect(sockets[1].closed).toBe(true);
    vi.advanceTimersByTime(2_000);
    expect(sockets).toHaveLength(3);
  });

  it('clears the command timeout even when a transport acknowledges synchronously', () => {
    const { client, sockets } = harness();
    const listener = observer();
    client.subscribe(executionSubscription, listener);
    const socket = sockets[0];
    const send = socket.send.bind(socket);
    socket.send = (data) => {
      send(data);
      socket.message(progressAck(executionSubscription));
    };
    socket.open();
    expect(listener.onChange.mock.lastCall?.[0].connection).toBe('subscribed');
    expect(vi.getTimerCount()).toBe(0);
    vi.advanceTimersByTime(30_000);
    expect(sockets).toHaveLength(1);
  });

  it('cancels reconnection when the last subscriber leaves and can be reused', () => {
    const { client, sockets } = harness();
    const stop = client.subscribe(executionSubscription, observer());
    sockets[0].disconnect();
    stop();
    vi.advanceTimersByTime(60_000);
    expect(sockets).toHaveLength(1);
    expect(vi.getTimerCount()).toBe(0);
    client.subscribe(documentSubscription, observer());
    expect(sockets).toHaveLength(2);
    client.dispose();
    expect(sockets[1].closed).toBe(true);
    expect(vi.getTimerCount()).toBe(0);
  });

  it('unsubscribes an abandoned in-flight resource before subscribing it again', () => {
    const { client, sockets } = harness();
    const stop = client.subscribe(executionSubscription, observer());
    client.subscribe(documentSubscription, observer());
    sockets[0].open();
    stop();
    client.subscribe(executionSubscription, observer());
    sockets[0].message(progressAck(executionSubscription));
    sockets[0].message(progressAck(documentSubscription));
    expect(sockets[0].sent[2]).toEqual({ action: 'UNSUBSCRIBE', ...executionSubscription });
    sockets[0].message({ type: 'UNSUBSCRIBED' });
    expect(sockets[0].sent[3]).toEqual({ action: 'SUBSCRIBE', ...executionSubscription });
  });
});

describe('progressWebSocketUrl', () => {
  it.each([
    ['/ws', 'http://localhost:5173/chat', 'ws://localhost:5173/ws/progress'],
    ['/ws/', 'https://example.com/app', 'wss://example.com/ws/progress'],
    ['wss://example.com/ws', 'http://localhost/', 'wss://example.com/ws/progress'],
    ['https://example.com/ws', 'http://localhost/', 'wss://example.com/ws/progress'],
  ])('resolves %s against %s', (base, page, expected) => {
    expect(progressWebSocketUrl(base, page)).toBe(expected);
  });
  it('rejects credentials, query tokens and non-WebSocket protocols', () => {
    for (const base of [
      'ws://user:secret@example.com/ws',
      'ws://example.com/ws?token=secret',
      'file:///ws',
    ]) {
      expect(() => progressWebSocketUrl(base, 'http://localhost/')).toThrow();
    }
  });
});
