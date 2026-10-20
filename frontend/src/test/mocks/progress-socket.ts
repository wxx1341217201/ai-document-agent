import type { ProgressEvent, ProgressSubscription } from '../../api/progress.contracts';
import { ProgressWebSocketClient, type ProgressSocket } from '../../api/progress-websocket';

/** Deterministic transport Mock; no real socket or backend authentication is involved. */
export class MockProgressSocket implements ProgressSocket {
  onopen: WebSocket['onopen'] = null;
  onmessage: WebSocket['onmessage'] = null;
  onclose: WebSocket['onclose'] = null;
  onerror: WebSocket['onerror'] = null;
  readonly sent: Record<string, unknown>[] = [];
  closed = false;

  send(data: string) {
    if (this.closed) throw new Error('Socket closed');
    this.sent.push(JSON.parse(data));
  }

  close() {
    this.closed = true;
  }

  open() {
    this.onopen?.call(this as unknown as WebSocket, new Event('open'));
  }

  message(data: unknown) {
    this.rawMessage(JSON.stringify(data));
  }

  rawMessage(data: unknown) {
    this.onmessage?.call(this as unknown as WebSocket, new MessageEvent('message', { data }));
  }

  disconnect() {
    this.onclose?.call(this as unknown as WebSocket, new CloseEvent('close', { code: 1006 }));
  }

  error() {
    this.onerror?.call(this as unknown as WebSocket, new Event('error'));
  }
}

export function createProgressHarness(
  options: ConstructorParameters<typeof ProgressWebSocketClient>[0] = {},
) {
  const sockets: MockProgressSocket[] = [];
  const client = new ProgressWebSocketClient({
    url: 'ws://localhost/ws/progress',
    jitterRatio: 0,
    createSocket: () => {
      const socket = new MockProgressSocket();
      sockets.push(socket);
      return socket;
    },
    ...options,
  });
  return { client, sockets };
}

export const executionSubscription = {
  resource: 'EXECUTION',
  knowledgeBaseId: 1,
  executionId: '12345678-1234-1234-1234-123456789abc',
} as const satisfies ProgressSubscription;

export const documentSubscription = {
  resource: 'DOCUMENT',
  knowledgeBaseId: 1,
  documentId: 101,
} as const satisfies ProgressSubscription;

export const ingestionId = '22345678-1234-1234-1234-123456789abc';

export function progressAck(
  target: ProgressSubscription,
  latestSequence = 0,
  streamId = ingestionId,
) {
  return {
    type: 'SUBSCRIBED',
    ...target,
    documentId: target.resource === 'DOCUMENT' ? target.documentId : null,
    executionId: target.resource === 'EXECUTION' ? target.executionId : streamId,
    latestSequence,
    statusUrl: '/untrusted-server-url-is-never-fetched',
  };
}

export function mockProgressEvent(
  sequence: number,
  overrides: Partial<ProgressEvent> = {},
): ProgressEvent {
  return {
    eventId: crypto.randomUUID(),
    executionId: executionSubscription.executionId,
    sequence,
    type: 'EXECUTION_STARTED',
    timestamp: '2026-10-11T08:30:00Z',
    payload: {
      status: 'RUNNING',
      summary: '执行已开始',
      stepId: null,
      toolName: null,
      documentId: null,
      errorCode: null,
    },
    ...overrides,
  };
}

export function mockDocumentEvent(
  sequence: number,
  overrides: Partial<ProgressEvent> = {},
): ProgressEvent {
  return mockProgressEvent(sequence, {
    executionId: ingestionId,
    type: 'PARSING',
    payload: {
      status: 'PROCESSING',
      summary: '正在解析文档',
      stepId: null,
      toolName: null,
      documentId: 101,
      errorCode: null,
    },
    ...overrides,
  });
}
