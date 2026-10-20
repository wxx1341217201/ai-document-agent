import { expect, test, type Page, type WebSocketRoute } from '@playwright/test';
import type { AgentMode, ExecutionResponse } from '../src/api/contracts';
import { completeMockPlan, mockPlanExecution } from '../src/test/mocks/agent-panel-fixtures';
import { mockProgressEvent, progressAck } from '../src/test/mocks/progress-socket';
import { mockDocumentChunks } from '../src/test/mocks/document-preview-fixtures';

/** Browser REST/WS Mocks only; these scenarios do not connect to a real model or database. */
async function mockPanelBackend(
  page: Page,
  mode: AgentMode = 'PLAN_EXECUTE_REFLECT',
  rejectSocket = false,
) {
  const first = { ...mockPlanExecution(), agentMode: mode };
  const authority = new Map<string, ExecutionResponse>([[first.executionId, first]]);
  const sockets = new Map<string, WebSocketRoute>();
  const subscriptions: string[] = [];
  const closed = new Set<string>();
  const state = { posts: 0, gets: 0, readError: false, next: first, authority };
  const timestamp = first.createdAt;
  const pageData = (content: unknown[]) => ({
    content,
    page: 0,
    size: 20,
    totalElements: content.length,
    totalPages: content.length ? 1 : 0,
  });
  await page.route('**/api/v1/**', async (route) => {
    const request = route.request();
    const url = new URL(request.url());
    let data: unknown;
    if (url.pathname === '/api/v1/knowledge-bases/1') {
      data = {
        id: 1,
        name: 'FE09 Mock 知识库',
        description: null,
        createdAt: timestamp,
        updatedAt: timestamp,
      };
    } else if (url.pathname.endsWith('/conversations')) {
      data = pageData([
        {
          id: 303,
          knowledgeBaseId: 1,
          title: 'FE09 Mock 执行可视化',
          agentMode: mode,
          createdAt: timestamp,
          updatedAt: timestamp,
        },
      ]);
    } else if (url.pathname.endsWith('/messages')) data = pageData([]);
    else if (url.pathname === '/api/v1/knowledge-bases/1/documents/101/chunks/1001/context')
      data = mockDocumentChunks;
    else if (url.pathname === '/api/v1/agent/executions' && request.method() === 'POST') {
      expect(request.postDataJSON()).toMatchObject({
        knowledgeBaseId: 1,
        conversationId: 303,
        agentMode: mode,
      });
      expect(request.headers()['idempotency-key']).toMatch(/^[0-9a-f-]{36}$/);
      state.posts += 1;
      data = state.next;
      authority.set(state.next.executionId, state.next);
    } else if (url.pathname.startsWith('/api/v1/agent/executions/') && request.method() === 'GET') {
      expect(url.searchParams.get('knowledgeBaseId')).toBe('1');
      state.gets += 1;
      if (state.readError) {
        await route.fulfill({
          status: 503,
          json: {
            code: 'UNAVAILABLE',
            message: 'Mock 详情暂不可用',
            data: null,
            traceId: 'fe09-e2e',
          },
        });
        return;
      }
      data = authority.get(url.pathname.split('/').at(-1)!);
      expect(data).toBeDefined();
    } else
      throw new Error('Unexpected FE09 Mock REST path: ' + request.method() + ' ' + url.pathname);
    await route.fulfill({
      status: request.method() === 'POST' ? 202 : 200,
      json: { code: 'SUCCESS', message: 'OK', data },
    });
  });
  await page.routeWebSocket('**/ws/progress', (socket) => {
    let subscribedId = '';
    socket.onClose(() => closed.add(subscribedId));
    socket.onMessage((data) => {
      const command = JSON.parse(String(data));
      expect(command).toMatchObject({ knowledgeBaseId: 1, resource: 'EXECUTION' });
      if (command.action === 'SUBSCRIBE') {
        subscribedId = command.executionId;
        subscriptions.push(subscribedId);
        sockets.set(subscribedId, socket);
        socket.send(
          JSON.stringify(
            rejectSocket
              ? { type: 'SUBSCRIPTION_REJECTED', code: 'UNAVAILABLE' }
              : progressAck({
                  resource: 'EXECUTION',
                  knowledgeBaseId: 1,
                  executionId: subscribedId,
                }),
          ),
        );
      } else {
        expect(command.action).toBe('UNSUBSCRIBE');
        socket.send(JSON.stringify({ type: 'UNSUBSCRIBED' }));
      }
    });
  });
  return {
    first,
    state,
    subscriptions,
    closed,
    send: (id: string, event: unknown) => sockets.get(id)!.send(JSON.stringify(event)),
  };
}

test('FE09：桌面 Plan 实时步骤、详情重试、引用、历史执行选择和窄屏 Drawer', async ({ page }) => {
  await page.setViewportSize({ width: 1600, height: 1000 });
  const backend = await mockPanelBackend(page);
  await page.goto('/knowledge-bases/1/chat/303');
  const panel = page.getByRole('region', { name: 'Agent 面板', exact: true });
  await expect(panel).toHaveCount(0);
  await page.getByRole('button', { name: 'Agent 面板', exact: true }).click();
  await expect(panel.getByText('提交问题后，在这里查看执行步骤与工具调用。')).toBeVisible();
  const input = page.getByLabel('消息', { exact: true });
  await expect(input).toBeEnabled();
  await input.fill('比较文档中的重试策略');
  await input.press('Enter');
  await expect(panel.getByText('Plan / Step Timeline')).toBeVisible();
  await expect(panel.locator('[data-step-id="r0_S2"]')).toHaveClass(/agent-step-current/);
  await expect(panel.getByText('依赖：r0_S1', { exact: true })).toBeVisible();
  await expect(panel.getByText('320 ms')).toBeVisible();
  await expect.poll(() => backend.subscriptions.length).toBe(1);
  const first = backend.first;
  const advanced = {
    ...first,
    currentStep: 'r0_S3',
    steps: first.steps.map((step) =>
      step.stepId === 'r0_S2'
        ? { ...step, status: 'SUCCEEDED' }
        : step.stepId === 'r0_S3'
          ? { ...step, status: 'RUNNING' }
          : step,
    ),
  };
  backend.state.authority.set(first.executionId, advanced);
  const beforeStepEvent = backend.state.gets;
  backend.send(
    first.executionId,
    mockProgressEvent(1, {
      executionId: first.executionId,
      type: 'STEP_STARTED',
      payload: {
        stepId: 'r0_S3',
        status: 'RUNNING',
        summary: '步骤状态已更新',
        toolName: null,
        documentId: null,
        errorCode: null,
      },
    }),
  );
  await expect(panel.locator('[data-step-id="r0_S3"]')).toHaveClass(/agent-step-current/);
  await expect(
    panel.locator('[data-step-id="r0_S2"]').getByLabel('步骤状态：SUCCEEDED'),
  ).toBeVisible();
  expect(backend.state.gets).toBeGreaterThan(beforeStepEvent);
  await page.emulateMedia({ reducedMotion: 'reduce' });
  expect(
    await panel
      .locator('.agent-running-dot')
      .first()
      .evaluate((element) => getComputedStyle(element).animationName),
  ).toBe('none');
  await page.screenshot({
    path: 'test-results/fe09-agent-panel-desktop.png',
    fullPage: true,
    animations: 'disabled',
  });

  backend.state.readError = true;
  await panel.getByRole('button', { name: '刷新 Agent 详情' }).click();
  await expect(panel.getByText('Mock 详情暂不可用')).toBeVisible();
  await expect(panel.locator('[data-step-id="r0_S3"]')).toBeVisible();
  backend.state.readError = false;
  await panel.getByRole('button', { name: '重试加载 Agent 详情' }).click();
  await expect(panel.getByText('Mock 详情暂不可用')).toHaveCount(0);
  expect(backend.state.posts).toBe(1);

  const completed = completeMockPlan(first);
  backend.state.authority.set(first.executionId, completed);
  backend.send(
    first.executionId,
    mockProgressEvent(2, { executionId: first.executionId, type: 'EXECUTION_COMPLETED' }),
  );
  await expect(panel.getByText('证据覆盖检查通过')).toBeVisible();
  await expect(panel.getByText('42 ms')).toBeVisible();
  await expect(input).toBeEnabled();
  await expect.poll(() => backend.closed.has(first.executionId)).toBe(true);
  await expect(panel.locator('.agent-running-dot')).toHaveCount(0);

  const second = {
    ...mockPlanExecution(),
    status: 'FAILED' as const,
    retryable: true,
    errorCode: 'TOOL_TIMEOUT',
  };
  backend.state.next = second;
  await input.fill('第二轮问题');
  await input.press('Enter');
  await expect(panel.getByText('执行失败 · 可重试')).toBeVisible();
  await expect(panel.locator('.agent-execution-details')).toHaveAttribute(
    'data-execution-id',
    second.executionId,
  );
  const executionSelect = panel.getByRole('combobox', { name: '选择 Agent 执行' });
  await executionSelect.focus();
  await executionSelect.press('ArrowDown');
  await page
    .getByText(`第 1 次 · ${first.executionId.slice(0, 8)}`, { exact: true })
    .last()
    .click();
  await expect(panel.locator('.agent-execution-details')).toHaveAttribute(
    'data-execution-id',
    first.executionId,
  );
  expect(backend.state.posts).toBe(2);

  await page.setViewportSize({ width: 390, height: 844 });
  await expect(page.getByRole('region', { name: 'Agent 面板', exact: true })).toHaveCount(0);
  await page.getByRole('button', { name: 'Agent 面板', exact: true }).click();
  const drawer = page.getByRole('dialog', { name: 'Agent 执行详情' });
  await expect(drawer).toBeVisible();
  await expect(drawer.locator('.agent-execution-details')).toHaveAttribute(
    'data-execution-id',
    first.executionId,
  );
  await page.screenshot({
    path: 'test-results/fe09-agent-panel-mobile.png',
    fullPage: true,
    animations: 'disabled',
  });
  await drawer.getByRole('region', { name: '执行引用' }).getByRole('button').click();
  await expect(page.getByRole('dialog', { name: '[C1] 引用详情' })).toBeVisible();
  await expect(drawer).not.toBeVisible();
  await page
    .getByRole('dialog', { name: '[C1] 引用详情' })
    .getByRole('button', { name: '技术信息' })
    .click();
  await expect(
    page.getByRole('dialog', { name: '[C1] 引用详情' }).getByText('1001', { exact: true }),
  ).toBeVisible();
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(
    true,
  );
});

test('FE09：ReAct 摘要、连接降级、FAILED / SKIPPED 及隐藏推理过滤', async ({ page }) => {
  await page.setViewportSize({ width: 1440, height: 1000 });
  const backend = await mockPanelBackend(page, 'REACT', true);
  const source = backend.first;
  source.steps = [
    {
      stepId: 'tool1',
      kind: 'TOOL',
      operation: 'searchKnowledgeBase',
      status: 'COMPLETED',
      dependsOn: [],
      errorCode: null,
    },
    {
      stepId: 'answer',
      kind: 'GENERATION',
      operation: 'cited_answer',
      status: 'RUNNING',
      dependsOn: [],
      errorCode: null,
    },
  ];
  source.currentStep = 'answer';
  source.toolCalls = source.toolCalls.map((tool) => ({
    ...tool,
    status: 'COMPLETED',
    argumentsSummary: {
      queryLength: '12',
      topK: '8',
      thoughts: 'HIDDEN_REASONING',
      rawArguments: '<img onerror=alert(1)>',
    },
  }));
  await page.goto('/knowledge-bases/1/chat/303');
  const input = page.getByLabel('消息', { exact: true });
  await input.fill('ReAct 测试');
  await input.press('Enter');
  const panel = page.getByRole('region', { name: 'Agent 面板', exact: true });
  await page.getByRole('button', { name: 'Agent 面板', exact: true }).click();
  await expect(panel.getByText('Step Timeline', { exact: true })).toBeVisible();
  await expect(panel.getByText('实时进度已降级')).toBeVisible();
  await expect(
    panel.locator('[data-step-id="tool1"]').getByLabel('步骤状态：COMPLETED'),
  ).toBeVisible();
  await expect(panel).not.toContainText('HIDDEN_REASONING');
  await expect(panel.locator('img[onerror]')).toHaveCount(0);
  const failed = {
    ...source,
    status: 'FAILED' as const,
    retryable: false,
    errorCode: 'TOOL_TIMEOUT',
    steps: source.steps.map((step) => ({
      ...step,
      status: step.stepId === 'tool1' ? 'FAILED' : 'SKIPPED',
      errorCode: step.stepId === 'tool1' ? 'TOOL_TIMEOUT' : null,
    })),
    toolCalls: source.toolCalls.map((tool) => ({
      ...tool,
      status: 'FAILED',
      elapsedMillis: null,
      errorCode: 'TOOL_TIMEOUT',
    })),
  };
  backend.state.authority.set(source.executionId, failed);
  await panel.getByRole('button', { name: '刷新 Agent 详情' }).click();
  await expect(panel.getByText('执行失败 · 当前不可重试')).toBeVisible();
  await expect(
    panel.locator('[data-step-id="answer"]').getByLabel('步骤状态：SKIPPED'),
  ).toBeVisible();
  await expect(panel.getByText('耗时未知')).toBeVisible();
  await expect(panel.locator('.agent-running-dot')).toHaveCount(0);
  await expect(panel.getByRole('button', { name: /Resume|恢复任务|重试任务|Cancel/ })).toHaveCount(
    0,
  );
  await expect(input).toBeEnabled();
  expect(backend.state.posts).toBe(1);
});
