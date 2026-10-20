import { expect, test, type Page } from '@playwright/test';
import { createMockPdf } from '../src/test/mocks/document-preview-fixtures';
import { mockWorkflowBackend } from './helpers/workflow-mock';

async function createKnowledgeBase(page: Page) {
  await page.goto('/knowledge-bases');
  await page
    .getByRole('button', { name: /新建知识库/ })
    .first()
    .click();
  const form = page.getByRole('dialog', { name: '新建知识库' });
  await form.getByLabel('名称', { exact: true }).fill('FE13 闭环 Mock 知识库');
  await form.getByRole('button', { name: /创\s*建/ }).click();
  await page.getByRole('link', { name: 'FE13 闭环 Mock 知识库' }).click();
}
async function createConversation(page: Page, title: string, mode = 'Retrieval-First') {
  await page.getByRole('button', { name: '高级会话' }).click();
  const modal = page.getByRole('dialog', { name: '创建会话' });
  await modal.getByLabel('会话标题').fill(title);
  if (mode !== 'Retrieval-First') {
    await modal.getByText('Retrieval-First', { exact: true }).click();
    await page.getByText(mode, { exact: true }).last().click();
  }
  await modal.getByRole('button', { name: /创\s*建/ }).click();
  await expect(modal).toBeHidden();
  await expect(page.getByRole('heading', { name: title, exact: true })).toBeVisible();
  await expect(page.getByLabel('消息', { exact: true })).toBeEnabled();
}

test('FE13 九步闭环：创建、TXT/PDF、READY、引用、实时 Agent、失败 Resume、刷新恢复（REST/WS Mock）', async ({
  page,
}) => {
  test.setTimeout(120_000);
  const backend = await mockWorkflowBackend(page);
  await createKnowledgeBase(page);
  // Upload genuine TXT/PDF bytes; their processing and citations are explicitly Mock.
  await page.locator('input[type="file"]').setInputFiles([
    { name: 'evidence.pdf', mimeType: 'application/pdf', buffer: Buffer.from(createMockPdf()) },
    {
      name: 'evidence.txt',
      mimeType: 'text/plain',
      buffer: Buffer.from('幂等键避免重复执行，恢复时核对服务端状态。'),
    },
  ]);
  await expect(page.getByRole('link', { name: 'evidence.pdf' })).toBeVisible();
  await expect(page.getByRole('status', { name: '状态：已就绪' })).toHaveCount(2, {
    timeout: 20_000,
  });
  await page.getByRole('button', { name: /开始问答/ }).click();
  await createConversation(page, '引用验证');
  await page.getByLabel('消息', { exact: true }).fill('文档怎样描述幂等恢复？');
  await page.getByLabel('消息', { exact: true }).press('Enter');
  await expect(page.getByText('Mock 回答', { exact: true })).toBeVisible();
  await page
    .getByLabel('当前会话', { exact: true })
    .getByRole('button', { name: '[C1] evidence.pdf', exact: true })
    .click();
  const citation = page.getByRole('dialog', { name: '[C1] 引用详情' });
  await expect(citation.getByText('第 1 页', { exact: true }).first()).toBeVisible();
  await expect(citation.getByText('Mock PDF 证据：幂等键避免重复执行。').last()).toBeVisible();
  await citation.getByRole('button', { name: '查看文档' }).click();
  await expect(page).toHaveURL(/documents\/101\?page=1&chunkId=\d+/);
  await expect(page.getByLabel('PDF 文档预览')).toHaveAttribute('data', /blob:.*#page=1/);
  const downloadEvent = page.waitForEvent('download');
  await page.getByRole('button', { name: /下载原文件/ }).click();
  expect((await downloadEvent).suggestedFilename()).toBe('evidence.pdf');
  await page.goto('/knowledge-bases/1/chat/301');
  await expect(page.getByText('Mock 回答', { exact: true })).toBeVisible();
  await createConversation(page, '执行恢复验证', 'Plan-Execute-Reflect');
  await page.getByLabel('消息', { exact: true }).fill('比较文档里的恢复策略');
  await page.getByLabel('消息', { exact: true }).press('Enter');
  const reply = page.getByLabel('Agent 执行回复', { exact: true });
  await expect(reply).toHaveAttribute('data-execution-id', /^[0-9a-f-]{36}$/);
  const id = (await reply.getAttribute('data-execution-id'))!;
  await expect
    .poll(() => [...backend.subscriptions.values()].some((item) => item.resource === 'EXECUTION'))
    .toBe(true);
  backend.progress(id);
  await page.getByRole('button', { name: 'Agent 面板', exact: true }).click();
  await expect(page.getByLabel('执行步骤')).toBeVisible();
  await expect(page.getByText('步骤状态已更新').first()).toBeVisible();
  backend.fail(id);
  await expect(reply.getByRole('status', { name: '状态：失败' })).toBeVisible();
  await reply.getByRole('link', { name: '打开任务详情' }).click();
  await page.getByRole('button', { name: 'Resume · 恢复任务' }).click();
  await expect(page.getByText('恢复请求已接受')).toBeVisible();
  await expect(page.getByRole('button', { name: 'Resume · 恢复任务' })).toBeDisabled();
  await page.reload();
  await expect(page.getByRole('heading', { name: 'Agent 执行详情' })).toBeVisible();
  await expect(page.getByRole('status', { name: '状态：执行中' }).first()).toBeVisible();
  await expect
    .poll(() => [...backend.subscriptions.values()].some((item) => item.resource === 'EXECUTION'))
    .toBe(true);
  backend.complete(id);
  await expect(page.getByText('执行已完成', { exact: true })).toBeVisible();
  await page.reload();
  await expect(page.getByText('Mock 执行回答', { exact: true })).toBeVisible();
  expect(backend.posts).toEqual({ resume: 1, cancel: 0, execution: 1 });
  await page.goto('/');
  await expect(page.getByText('最近知识库', { exact: true })).toBeVisible();
  await expect(page.getByRole('link', { name: 'evidence.pdf' })).toBeVisible();
  await expect(page.getByText('MySQL')).toHaveCount(0);
  await page.screenshot({ path: 'output/playwright/fe12-dashboard-desktop.png', fullPage: true });
});

test('FE10 并发 Resume 409、暂停刷新、Cancel 和窄屏（REST/WS Mock）', async ({ page }) => {
  test.setTimeout(90_000);
  const backend = await mockWorkflowBackend(page);
  await createKnowledgeBase(page);
  await page.getByRole('button', { name: /开始问答/ }).click();
  await createConversation(page, '并发恢复', 'ReAct');
  await page.getByLabel('消息', { exact: true }).fill('测试取消与恢复');
  await page.getByLabel('消息', { exact: true }).press('Enter');
  const reply = page.getByLabel('Agent 执行回复', { exact: true });
  await expect(reply).toHaveAttribute('data-execution-id', /^[0-9a-f-]{36}$/);
  const id = (await reply.getAttribute('data-execution-id'))!;
  const execution = backend.executions.get(id)!;
  execution.status = 'PAUSED';
  await page.goto(`/agent-executions/${id}?knowledgeBaseId=1`);
  await page.reload();
  await expect(page.getByRole('button', { name: 'Resume · 恢复任务' })).toBeEnabled();
  backend.concurrentResumeOnce();
  await page.getByRole('button', { name: 'Resume · 恢复任务' }).click();
  await expect(page.getByText('任务状态已变化，或另一请求已恢复任务')).toBeVisible();
  await expect(page.getByRole('button', { name: 'Resume · 恢复任务' })).toBeDisabled();
  expect(backend.posts.resume).toBe(1);
  await page.setViewportSize({ width: 390, height: 844 });
  await page.getByRole('button', { name: 'Cancel · 取消任务' }).click();
  await page
    .getByRole('dialog', { name: '确认取消执行任务' })
    .getByRole('button', { name: '确认取消任务' })
    .click();
  await expect(page.getByText('任务已取消', { exact: true })).toBeVisible();
  await expect(page.getByRole('button', { name: 'Cancel · 取消任务' })).toBeDisabled();
  await page.reload();
  await expect(page.getByRole('status', { name: '状态：已取消' }).first()).toBeVisible();
  expect(backend.posts.cancel).toBe(1);
  expect(await page.evaluate(() => document.documentElement.scrollWidth)).toBeLessThanOrEqual(390);
  await page.screenshot({ path: 'output/playwright/fe10-execution-mobile.png', fullPage: true });
});
