import { expect, test, type Page } from '@playwright/test';
import type { Conversation, ConversationMessage, RagCitation } from '../src/api/contracts';
import { completeMockExecution, mockExecution } from '../src/test/mocks/execution-fixtures';

/** Node-side REST Mock survives page.reload; browser/query state is discarded by the reload. */
async function mockChatBackend(page: Page) {
  const timestamp = '2026-10-11T08:30:00';
  const citation: RagCitation = {
    citationId: 'C1',
    documentId: 101,
    documentName: 'design.pdf',
    chunkId: 1001,
    pageFrom: 5,
    pageTo: 5,
    quote: '已处理的幂等键不会重复执行。',
  };
  const knowledgeBase = {
    id: 1,
    name: 'FE06 Mock 知识库',
    description: null,
    createdAt: timestamp,
    updatedAt: timestamp,
  };
  const conversations: Conversation[] = [
    {
      id: 301,
      knowledgeBaseId: 1,
      title: '已有会话',
      agentMode: 'RETRIEVAL_FIRST',
      createdAt: timestamp,
      updatedAt: timestamp,
    },
  ];
  const messages: Record<number, ConversationMessage[]> = { 301: [] };
  let nextId = 302;
  let nextMessageId = 1;
  let historyReads = 0;
  const postedContents: string[] = [];
  await page.route('**/api/v1/**', async (route) => {
    const request = route.request();
    const path = new URL(request.url()).pathname;
    const method = request.method();
    let data: unknown = null;
    let status = 200;
    let code = 'SUCCESS';
    if (path === '/api/v1/knowledge-bases/1' && method === 'GET') {
      data = knowledgeBase;
    } else if (path === '/api/v1/knowledge-bases/1/conversations' && method === 'GET') {
      data = {
        content: [...conversations].reverse(),
        page: 0,
        size: 20,
        totalElements: conversations.length,
        totalPages: conversations.length ? 1 : 0,
      };
    } else if (path === '/api/v1/knowledge-bases/1/conversations' && method === 'POST') {
      const input = request.postDataJSON();
      expect(Object.keys(input).sort()).toEqual(['agentMode', 'title']);
      const item: Conversation = {
        id: nextId++,
        knowledgeBaseId: 1,
        title: input.title,
        agentMode: input.agentMode,
        createdAt: timestamp,
        updatedAt: timestamp,
      };
      conversations.push(item);
      messages[item.id] = [];
      status = 201;
      data = item;
    } else {
      const match = path.match(/^\/api\/v1\/conversations\/(\d+)(\/messages)?$/);
      const id = Number(match?.[1]);
      if (!match || !conversations.some((item) => item.id === id)) {
        status = 404;
        code = 'CONVERSATION_NOT_FOUND';
      } else if (match[2] && method === 'GET') {
        historyReads += 1;
        data = {
          content: messages[id],
          page: 0,
          size: 100,
          totalElements: messages[id].length,
          totalPages: messages[id].length ? 1 : 0,
        };
      } else if (match[2] && method === 'POST') {
        const input = request.postDataJSON();
        expect(Object.keys(input)).toEqual(['content']);
        postedContents.push(input.content);
        const base = {
          conversationId: id,
          createdAt: timestamp,
          citations: [],
          modelName: null,
          tokenUsage: null,
        };
        const userMessage: ConversationMessage = {
          ...base,
          id: nextMessageId++,
          sequenceNo: messages[id].length + 1,
          role: 'USER',
          content: input.content,
        };
        const assistantMessage: ConversationMessage = {
          ...base,
          id: nextMessageId++,
          sequenceNo: userMessage.sequenceNo + 1,
          role: 'ASSISTANT',
          content:
            '**Mock 第 ' +
            (messages[id].length / 2 + 1) +
            ' 轮回答**：幂等键用于跳过重复处理。[C1]',
          citations: [citation],
          modelName: 'mock-model',
        };
        messages[id].push(userMessage, assistantMessage);
        data = { userMessage, assistantMessage };
      } else if (!match[2] && method === 'DELETE') {
        conversations.splice(
          conversations.findIndex((item) => item.id === id),
          1,
        );
        delete messages[id];
      } else {
        status = 405;
        code = 'METHOD_NOT_ALLOWED';
      }
    }
    await route.fulfill({
      status,
      json: {
        code,
        message: code === 'SUCCESS' ? 'OK' : 'Mock request failed',
        data,
        traceId: 'e2e-chat-mock',
      },
    });
  });
  return { conversations, messages, postedContents, getHistoryReads: () => historyReads };
}

test('FE06 创建多轮会话、刷新恢复、引用和删除（REST Mock）', async ({ page }) => {
  const backend = await mockChatBackend(page);
  await page.goto('/knowledge-bases/1/chat');
  await page.getByRole('button', { name: '高级会话' }).click();
  const modal = page.getByRole('dialog', { name: '创建会话' });
  await modal.getByLabel('会话标题').fill('刷新恢复验证');
  await modal.getByRole('button', { name: '创 建' }).click();
  await expect(page).toHaveURL(/\/chat\/302$/);
  await expect(page.getByText('还没有消息，发送第一个问题开始对话。')).toBeVisible();
  await page.getByLabel('消息', { exact: true }).fill('如何避免重复处理？');
  await page.getByLabel('消息', { exact: true }).press('Enter');
  await expect(page.getByText('Mock 第 1 轮回答', { exact: true })).toBeVisible();
  await page.getByLabel('消息', { exact: true }).fill('继续解释上一轮');
  await page.getByRole('button', { name: '发送', exact: true }).click();
  await expect(page.getByText('Mock 第 2 轮回答', { exact: true })).toBeVisible();
  await expect(page.getByLabel('消息', { exact: true })).toBeEnabled();
  expect(backend.postedContents).toEqual(['如何避免重复处理？', '继续解释上一轮']);
  const beforeReload = backend.getHistoryReads();
  await page.reload();
  await expect(page.getByText('Mock 第 1 轮回答', { exact: true })).toBeVisible();
  await expect(page.getByText('Mock 第 2 轮回答', { exact: true })).toBeVisible();
  await expect(page.getByText('继续解释上一轮', { exact: true })).toBeVisible();
  expect(backend.getHistoryReads()).toBeGreaterThan(beforeReload);
  await page.getByRole('button', { name: '[C1] design.pdf', exact: true }).last().click();
  const citation = page.getByRole('dialog', { name: '[C1] 引用详情' });
  await expect(citation.getByText('第 5 页', { exact: true })).toBeVisible();
  await expect(citation.getByText('已处理的幂等键不会重复执行。')).toBeVisible();
  await citation.getByRole('button', { name: /关闭|Close/ }).click();
  await page.getByRole('button', { name: '删除会话 刷新恢复验证' }).click();
  await page
    .getByRole('dialog', { name: '删除会话' })
    .getByRole('button', { name: '删 除' })
    .click();
  await expect(page).toHaveURL(/\/knowledge-bases\/1\/chat$/);
  await expect(page.getByRole('button', { name: '打开会话 刷新恢复验证' })).toHaveCount(0);
  expect(backend.messages[302]).toBeUndefined();
});

test('FE06 小屏会话抽屉与 Plan-Execute-Reflect 创建（REST Mock）', async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 });
  const backend = await mockChatBackend(page);
  await page.goto('/knowledge-bases/1/chat/301');
  await expect(page.getByText('还没有消息，发送第一个问题开始对话。')).toBeVisible();
  await page.getByRole('button', { name: '会话列表', exact: true }).click();
  const sidebar = page.getByRole('dialog', { name: '会话列表' });
  await expect(sidebar.getByRole('button', { name: '打开会话 已有会话' })).toBeVisible();
  await sidebar.getByRole('button', { name: '高级会话' }).click();
  const modal = page.getByRole('dialog', { name: '创建会话' });
  await modal.getByLabel('会话标题').fill('复杂问题');
  await modal.getByText('Retrieval-First', { exact: true }).click();
  await page.getByText('Plan-Execute-Reflect', { exact: true }).last().click();
  await modal.getByRole('button', { name: '创 建' }).click();
  await expect(page).toHaveURL(/\/chat\/302$/);
  await expect(page.getByRole('heading', { name: '复杂问题' })).toBeVisible();
  expect(backend.conversations.at(-1)?.agentMode).toBe('PLAN_EXECUTE_REFLECT');
  expect(await page.evaluate(() => document.documentElement.scrollWidth)).toBeLessThanOrEqual(390);
});

for (const agentMode of ['REACT', 'PLAN_EXECUTE_REFLECT'] as const) {
  test(`FE07 ${agentMode} 执行关联、切换会话、REST 刷新和回答引用（REST Mock）`, async ({
    page,
  }) => {
    const backend = await mockChatBackend(page);
    const conversation: Conversation = {
      ...backend.conversations[0],
      id: 303,
      title: 'FE07 执行会话',
      agentMode,
    };
    backend.conversations.push(conversation);
    backend.messages[303] = [];
    const execution = mockExecution({
      knowledgeBaseId: 1,
      conversationId: 303,
      agentMode,
      question: '执行问题',
    });
    let posts = 0;
    let gets = 0;
    let authority = execution;
    await page.route('**/api/v1/agent/executions**', async (route) => {
      const request = route.request();
      const url = new URL(request.url());
      let data = execution;
      if (request.method() === 'POST') {
        posts += 1;
        expect(request.postDataJSON()).toEqual({
          knowledgeBaseId: 1,
          conversationId: 303,
          agentMode,
          question: '执行问题',
        });
        expect(request.headers()['idempotency-key']).toMatch(/^[0-9a-f-]{36}$/);
      } else {
        gets += 1;
        expect(url.pathname).toBe('/api/v1/agent/executions/' + execution.executionId);
        expect(url.searchParams.get('knowledgeBaseId')).toBe('1');
        data = authority;
      }
      await route.fulfill({
        status: request.method() === 'POST' ? 202 : 200,
        json: { code: 'SUCCESS', message: 'OK', data, traceId: 'e2e-execution-mock' },
      });
    });

    await page.goto('/knowledge-bases/1/chat/303');
    const input = page.getByLabel('消息', { exact: true });
    await expect(input).toBeEnabled();
    await input.fill('执行问题');
    await input.press('Enter');
    const reply = page.getByLabel('Agent 执行回复', { exact: true });
    await expect(reply).toHaveAttribute('data-execution-id', execution.executionId);
    const assistantId = await reply.getAttribute('data-assistant-message-id');
    await expect(input).toBeDisabled();
    await expect(page.getByRole('button', { name: '删除会话 FE07 执行会话' })).toBeDisabled();
    await page.getByRole('button', { name: '打开会话 已有会话' }).click();
    await expect(reply).toHaveCount(0);
    await page.getByRole('button', { name: '打开会话 FE07 执行会话' }).click();
    await expect(reply).toHaveAttribute('data-assistant-message-id', assistantId!);
    await expect(input).toBeDisabled();
    await reply.getByText('任务信息', { exact: true }).click();
    const readsBeforeRefresh = gets;
    authority = { ...execution, status: 'RUNNING' };
    await reply.getByRole('button', { name: '刷新执行状态' }).click();
    await expect(reply.getByLabel('状态：执行中')).toBeVisible();
    await expect(input).toBeDisabled();
    authority = completeMockExecution(execution);
    await reply.getByRole('button', { name: '刷新执行状态' }).click();
    await expect(reply.getByText('Mock 执行回答', { exact: true })).toBeVisible();
    await expect(input).toBeEnabled();
    expect(posts).toBe(1);
    expect(gets).toBeGreaterThanOrEqual(readsBeforeRefresh + 2);
    expect(backend.postedContents).toEqual([]);
    expect(backend.messages[303]).toEqual([]);
    await reply.getByRole('button', { name: '[C1] catalyst-paper.pdf', exact: true }).click();
    const citation = page.getByRole('dialog', { name: '[C1] 引用详情' });
    await citation.getByRole('button', { name: '技术信息' }).click();
    await expect(citation.getByText('1001', { exact: true })).toBeVisible();
    await expect(
      citation.getByText('消费者会先检查幂等键；已处理的消息不会再次执行业务逻辑。'),
    ).toBeVisible();
  });
}

test('直接提问创建默认会话，刷新恢复且不重复发送（桌面与手机）', async ({ page }) => {
  for (const width of [1440, 390]) {
    await page.setViewportSize({ width, height: 900 });
    const backend = await mockChatBackend(page);
    await page.goto('/knowledge-bases/1/chat');
    const input = page.getByLabel('消息', { exact: true });
    await expect(input).toBeVisible();
    expect(backend.conversations).toHaveLength(1);
    await expect(page.getByRole('dialog')).toHaveCount(0);
    await expect(page.getByRole('region', { name: 'Agent 面板', exact: true })).toHaveCount(0);
    await input.fill('如何避免重复处理？');
    await input.press('Enter');
    await expect(page).toHaveURL(/\/chat\/302$/);
    await expect(page.getByText('Mock 第 1 轮回答', { exact: true })).toBeVisible();
    expect(backend.conversations).toHaveLength(2);
    expect(backend.conversations[1]).toMatchObject({
      title: '如何避免重复处理？',
      agentMode: 'RETRIEVAL_FIRST',
    });
    expect(backend.postedContents).toEqual(['如何避免重复处理？']);
    await page.screenshot({ path: `output/playwright/ux-chat-${width}.png`, fullPage: true });
    await page.reload();
    await expect(page.getByText('Mock 第 1 轮回答', { exact: true })).toBeVisible();
    expect(backend.postedContents).toEqual(['如何避免重复处理？']);
    expect(await page.evaluate(() => document.documentElement.scrollWidth)).toBeLessThanOrEqual(
      width,
    );
    if (width === 390) {
      await page.getByRole('button', { name: '会话列表', exact: true }).click();
      const sidebar = page.getByRole('dialog', { name: '会话列表' });
      await sidebar.getByRole('button', { name: '新建会话', exact: true }).click();
      await expect(sidebar).toBeHidden();
    } else {
      await page.getByRole('button', { name: '新建会话', exact: true }).click();
    }
    await expect(page).toHaveURL(/\/chat$/);
    await expect(page.getByLabel('消息', { exact: true })).toHaveValue('');
    expect(backend.conversations).toHaveLength(2);
  }
});
