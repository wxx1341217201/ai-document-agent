import { readFile } from 'node:fs/promises';
import { fileURLToPath } from 'node:url';
import { expect, test, type Page } from '@playwright/test';
import { createMockPdf } from '../src/test/mocks/document-preview-fixtures';

// Collect real server frames without intercepting REST or WebSocket traffic.
function observeProgress(page: Page) {
  const frames: Record<string, unknown>[] = [];
  page.on('websocket', (socket) => {
    if (new URL(socket.url()).pathname !== '/ws/progress') return;
    socket.on('framereceived', ({ payload }) => {
      try {
        const frame: unknown = JSON.parse(String(payload));
        if (typeof frame === 'object' && frame !== null && !Array.isArray(frame)) {
          frames.push(frame as Record<string, unknown>);
        }
      } catch {
        // Malformed frames cannot count as evidence of realtime progress.
      }
    });
  });
  return frames;
}

test('真实后端：创建 KB、上传 TXT/PDF、实时 READY、真实 Citation、PER 实时步骤及刷新', async ({
  page,
}) => {
  // No intercepted requests. Files are synthetic test documents; ingestion, retrieval,
  // model answers and citation IDs must all come from the real backend.
  const frames = observeProgress(page);
  const name = `前端实际验收 ${Date.now()}`;
  await page.goto('/knowledge-bases');
  await page
    .getByRole('button', { name: /新建知识库/ })
    .first()
    .click();
  const form = page.getByRole('dialog', { name: '新建知识库' });
  await form.getByLabel('名称', { exact: true }).fill(name);
  const creation = page.waitForResponse(
    (response) =>
      new URL(response.url()).pathname === '/api/v1/knowledge-bases' &&
      response.request().method() === 'POST',
  );
  await form.getByRole('button', { name: /创\s*建/ }).click();
  const created = await (await creation).json();
  expect(created.code).toBe('SUCCESS');
  const kbId = created.data.id as number;
  expect(kbId).toBeGreaterThan(0);
  await page.goto(`/knowledge-bases/${kbId}`);
  const text = await readFile(
    fileURLToPath(new URL('./fixtures/idempotency.txt', import.meta.url)),
  );
  await page.locator('input[type="file"]').setInputFiles([
    { name: 'idempotency.txt', mimeType: 'text/plain', buffer: text },
    { name: 'preview-test.pdf', mimeType: 'application/pdf', buffer: Buffer.from(createMockPdf()) },
  ]);
  await expect(page.getByRole('status', { name: '状态：已就绪' })).toHaveCount(2, {
    timeout: 120_000,
  });
  const sourceLink = page.getByRole('link', { name: 'idempotency.txt', exact: true });
  const documentId = Number((await sourceLink.getAttribute('href'))!.split('/').at(-1));
  await expect
    .poll(() =>
      frames.some((frame) => frame.type === 'SUBSCRIBED' && frame.resource === 'DOCUMENT'),
    )
    .toBe(true);
  expect(
    frames.some((frame) =>
      ['PARSING', 'CHUNKING', 'INDEXING', 'DOCUMENT_READY'].includes(String(frame.type)),
    ),
  ).toBe(true);
  await page.getByRole('button', { name: /开始问答/ }).click();
  await page.getByRole('button', { name: '高级会话' }).click();
  let modal = page.getByRole('dialog', { name: '创建会话' });
  await modal.getByLabel('会话标题').fill('真实引用验收');
  await modal.getByRole('button', { name: /创\s*建/ }).click();
  await expect(page.getByRole('heading', { name: '真实引用验收', exact: true })).toBeVisible();
  await expect(page.getByLabel('消息', { exact: true })).toBeEnabled();
  const historyUrl = page.url();
  const response = page.waitForResponse(
    (value) =>
      /\/conversations\/\d+\/messages$/.test(new URL(value.url()).pathname) &&
      value.request().method() === 'POST',
  );
  await page
    .getByLabel('消息', { exact: true })
    .fill('并发恢复发生冲突时客户端应该怎么办？请引用任务恢复验收资料。');
  await page.getByLabel('消息', { exact: true }).press('Enter');
  const body = await (await response).json();
  expect(body.code).toBe('SUCCESS');
  const citations = body.data.assistantMessage.citations as {
    citationId: string;
    documentId: number;
    chunkId: number;
    quote: string;
  }[];
  const actual = citations.find((citation) => citation.documentId === documentId);
  expect(actual).toBeDefined();
  expect(actual!.chunkId).toBeGreaterThan(0);
  expect(actual!.quote.trim()).not.toBe('');
  await page
    .getByLabel('当前会话', { exact: true })
    .getByRole('button', { name: `[${actual!.citationId}] idempotency.txt`, exact: true })
    .click();
  const citationDrawer = page.getByRole('dialog', { name: `[${actual!.citationId}] 引用详情` });
  await expect(citationDrawer.locator('.citation-drawer-quote')).toHaveText(actual!.quote);
  await citationDrawer.getByRole('button', { name: '技术信息', exact: true }).click();
  await expect(
    citationDrawer.getByRole('row', { name: new RegExp(`^Chunk ID\\s*:\\s*${actual!.chunkId}$`) }),
  ).toBeVisible();
  await page.goto(historyUrl);
  await page.reload();
  await expect(
    page
      .getByLabel('当前会话', { exact: true })
      .getByRole('button', { name: `[${actual!.citationId}] idempotency.txt`, exact: true }),
  ).toBeVisible();
  await page.getByRole('button', { name: '高级会话' }).click();
  modal = page.getByRole('dialog', { name: '创建会话' });
  await modal.getByLabel('会话标题').fill('真实 PER 验收');
  await modal.getByText('Retrieval-First', { exact: true }).click();
  await page.getByText('Plan-Execute-Reflect', { exact: true }).last().click();
  await modal.getByRole('button', { name: /创\s*建/ }).click();
  await expect(page.getByRole('heading', { name: '真实 PER 验收', exact: true })).toBeVisible();
  await expect(page.getByLabel('消息', { exact: true })).toBeEnabled();
  await page
    .getByLabel('消息', { exact: true })
    .fill('结合资料，总结幂等检查、暂停与并发恢复的步骤。');
  await page.getByLabel('消息', { exact: true }).press('Enter');
  const reply = page.getByLabel('Agent 执行回复', { exact: true });
  await expect(reply).toHaveAttribute('data-execution-id', /^[0-9a-f-]{36}$/);
  const executionId = await reply.getAttribute('data-execution-id');
  await expect
    .poll(() =>
      frames.some((frame) => frame.type === 'SUBSCRIBED' && frame.executionId === executionId),
    )
    .toBe(true);
  await expect
    .poll(
      () =>
        frames.some(
          (frame) =>
            frame.executionId === executionId &&
            ['PLAN_CREATED', 'STEP_STARTED', 'TOOL_COMPLETED', 'STEP_COMPLETED'].includes(
              String(frame.type),
            ),
        ),
      { timeout: 60_000 },
    )
    .toBe(true);
  await reply.getByRole('link', { name: '打开任务详情' }).click();
  await expect(page.getByLabel('执行步骤').locator('[data-step-id]').first()).toBeVisible();
  await page.reload();
  await expect(page.getByRole('heading', { name: 'Agent 执行详情' })).toBeVisible();
  await expect(page.getByLabel('执行步骤').locator('[data-step-id]').first()).toBeVisible();
  await expect(page.getByRole('status', { name: '状态：已完成' }).first()).toBeVisible({
    timeout: 60_000,
  });
  await expect(page.getByText('执行已完成', { exact: true })).toBeVisible();
  await page.reload();
  await expect(page.getByText('执行已完成', { exact: true })).toBeVisible();
});

test('真实后端：预置 PAUSED/可重试 FAILED 的 Resume 与刷新恢复', async ({ page }) => {
  const executionId = process.env.PLAYWRIGHT_RESUME_EXECUTION_ID;
  const kbId = process.env.PLAYWRIGHT_RESUME_KB_ID;
  test.skip(
    !executionId || !kbId,
    '需要后端预置的可恢复任务，设置 PLAYWRIGHT_RESUME_EXECUTION_ID / PLAYWRIGHT_RESUME_KB_ID。',
  );
  await page.goto(
    `/agent-executions/${encodeURIComponent(executionId!)}?knowledgeBaseId=${encodeURIComponent(kbId!)}`,
  );
  const resume = page.getByRole('button', { name: 'Resume · 恢复任务' });
  await expect(resume).toBeEnabled();
  await resume.click();
  await expect(page.getByText('恢复请求已接受')).toBeVisible();
  await page.reload();
  await expect(resume).toBeDisabled();
  await expect(page.getByRole('heading', { name: 'Agent 执行详情' })).toBeVisible();
});
