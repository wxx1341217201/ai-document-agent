import { expect, test, type Page, type WebSocketRoute } from '@playwright/test';
import type { Conversation, DocumentRecord } from '../src/api/contracts';
import type { ProgressSubscription } from '../src/api/progress.contracts';
import { completeMockExecution, mockExecution } from '../src/test/mocks/execution-fixtures';
import {
  documentSubscription,
  mockDocumentEvent,
  mockProgressEvent,
  progressAck,
} from '../src/test/mocks/progress-socket';

/** Browser-native WebSocket + node-side REST/WS Mocks; not a real-backend acceptance test. */
async function mockProgressTransport(page: Page, target: ProgressSubscription) {
  const subscriptions: WebSocketRoute[] = [];
  const closed = new Set<WebSocketRoute>();
  let latestSequence = 0;
  await page.routeWebSocket('**/ws/progress', (socket) => {
    socket.onClose(() => closed.add(socket));
    socket.onMessage((data) => {
      const command = JSON.parse(String(data));
      if (command.action === 'SUBSCRIBE') {
        expect(command).toEqual({ action: 'SUBSCRIBE', ...target });
        subscriptions.push(socket);
        socket.send(JSON.stringify(progressAck(target, latestSequence)));
      } else {
        expect(command).toEqual({ action: 'UNSUBSCRIBE', ...target });
        socket.send(JSON.stringify({ type: 'UNSUBSCRIBED' }));
      }
    });
  });
  return {
    subscriptions,
    closed,
    send: (event: unknown) => subscriptions.at(-1)!.send(JSON.stringify(event)),
    setSequence: (sequence: number) => {
      latestSequence = sequence;
    },
  };
}

const timestamp = '2026-10-11T08:30:00';
const knowledgeBase = {
  id: 1,
  name: 'FE08 Mock 知识库',
  description: null,
  createdAt: timestamp,
  updatedAt: timestamp,
};
const pageData = (content: unknown[]) => ({
  content,
  page: 0,
  size: 20,
  totalElements: content.length,
  totalPages: content.length ? 1 : 0,
});

test('FE08：聊天实时进度、重复/乱序/缺口、断线重连及终态 REST 回答', async ({ page }) => {
  const conversation: Conversation = {
    id: 303,
    knowledgeBaseId: 1,
    title: '实时执行会话',
    agentMode: 'REACT',
    createdAt: timestamp,
    updatedAt: timestamp,
  };
  const execution = mockExecution({
    knowledgeBaseId: 1,
    conversationId: 303,
    agentMode: 'REACT',
    question: '实时问题',
  });
  const target: ProgressSubscription = {
    resource: 'EXECUTION',
    knowledgeBaseId: 1,
    executionId: execution.executionId,
  };
  const transport = await mockProgressTransport(page, target);
  let authority = execution;
  let gets = 0;
  let posts = 0;
  await page.route('**/api/v1/**', async (route) => {
    const request = route.request();
    const url = new URL(request.url());
    let data: unknown;
    if (url.pathname === '/api/v1/knowledge-bases/1') data = knowledgeBase;
    else if (url.pathname.endsWith('/conversations')) data = pageData([conversation]);
    else if (url.pathname.endsWith('/messages')) data = pageData([]);
    else if (url.pathname === '/api/v1/agent/executions' && request.method() === 'POST') {
      posts += 1;
      expect(request.headers()['idempotency-key']).toMatch(/^[0-9a-f-]{36}$/);
      data = execution;
    } else if (url.pathname === '/api/v1/agent/executions/' + execution.executionId) {
      expect(url.searchParams.get('knowledgeBaseId')).toBe('1');
      gets += 1;
      data = authority;
    } else throw new Error('Unexpected REST Mock path: ' + url.pathname);
    await route.fulfill({ json: { code: 'SUCCESS', message: 'OK', data } });
  });
  await page.goto('/knowledge-bases/1/chat/303');
  const input = page.getByLabel('消息', { exact: true });
  await expect(input).toBeEnabled();
  await input.fill('实时问题');
  await input.press('Enter');
  const reply = page.getByLabel('Agent 执行回复', { exact: true });
  await expect(reply).toHaveAttribute('data-execution-id', execution.executionId);
  await expect.poll(() => transport.subscriptions.length).toBe(1);
  const started = mockProgressEvent(1, { executionId: execution.executionId });
  transport.send(started);
  transport.send(started);
  await expect(reply.getByText(/执行已开始/)).toBeVisible();
  await expect(input).toBeDisabled();
  authority = { ...execution, status: 'RUNNING' };
  transport.send(mockProgressEvent(3, { executionId: execution.executionId }));
  transport.send(
    mockProgressEvent(2, {
      executionId: execution.executionId,
      type: 'PLAN_CREATED',
      payload: { ...started.payload, summary: '计划已创建' },
    }),
  );
  await expect(reply.getByRole('status', { name: '状态：执行中' })).toBeVisible();
  await expect(reply.getByText(/计划已创建/)).toHaveCount(0);
  const beforeReconnect = gets;
  transport.setSequence(7);
  await transport.subscriptions[0].close({ code: 1013, reason: 'Mock disconnect' });
  await expect.poll(() => transport.subscriptions.length).toBe(2);
  await expect.poll(() => gets).toBeGreaterThan(beforeReconnect);
  await expect(reply.getByText(/正在接收实时进度/)).toBeVisible();
  authority = completeMockExecution(execution);
  const terminal = mockProgressEvent(8, {
    executionId: execution.executionId,
    type: 'EXECUTION_COMPLETED',
  });
  transport.send(terminal);
  transport.send(terminal);
  await expect(reply.getByText('Mock 执行回答', { exact: true })).toBeVisible();
  await expect(input).toBeEnabled();
  await expect.poll(() => transport.closed.has(transport.subscriptions[1])).toBe(true);
  expect(posts).toBe(1);
});

for (const [label, path] of [
  ['详情', '/knowledge-bases/1/documents/101'],
  ['列表', '/knowledge-bases/1'],
] as const) {
  test(`FE08：文档${label}接收处理阶段并在终态刷新 REST`, async ({ page }) => {
    const transport = await mockProgressTransport(page, documentSubscription);
    let document: DocumentRecord = {
      id: 101,
      knowledgeBaseId: 1,
      originalName: 'live-progress.pdf',
      contentType: 'application/pdf',
      extension: 'pdf',
      sizeBytes: 100,
      sha256: 'mock-sha256',
      status: 'PROCESSING',
      errorCode: null,
      errorMessage: null,
      createdAt: timestamp,
      updatedAt: timestamp,
    };
    await page.route('**/api/v1/**', async (route) => {
      const path = new URL(route.request().url()).pathname;
      const data =
        path === '/api/v1/knowledge-bases/1'
          ? knowledgeBase
          : path.endsWith('/documents')
            ? pageData([document])
            : document;
      await route.fulfill({ json: { code: 'SUCCESS', message: 'OK', data } });
    });
    await page.goto(path);
    await expect(page.getByRole('status', { name: '状态：处理中' })).toBeVisible();
    await expect.poll(() => transport.subscriptions.length).toBe(1);
    transport.send(mockDocumentEvent(1));
    await expect(page.getByText(/正在解析文档/)).toBeVisible();
    const indexing = mockDocumentEvent(3, {
      type: 'INDEXING',
      payload: { ...mockDocumentEvent(1).payload, summary: '正在建立索引' },
    });
    transport.send(indexing);
    transport.send(indexing);
    await expect(page.getByText(/正在建立索引/)).toBeVisible();
    document = { ...document, status: 'READY' };
    transport.send(mockDocumentEvent(4, { type: 'DOCUMENT_READY' }));
    await expect(page.getByRole('status', { name: '状态：已就绪' })).toBeVisible();
    await expect.poll(() => transport.closed.has(transport.subscriptions[0])).toBe(true);
  });
}
