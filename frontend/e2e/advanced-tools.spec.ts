import { expect, test } from '@playwright/test';

test.setTimeout(60_000);

test('任务接口：真实摘要分页、组合筛选及参数边界', async ({ request }) => {
  const allResponse = await request.get('/api/v1/agent/executions?page=0&size=100');
  expect(allResponse.status()).toBe(200);
  const all = (await allResponse.json()).data;
  const pages = [];
  for (let page = 0; page < Math.min(2, Math.ceil(all.totalElements / 5)); page++) {
    const response = await request.get(`/api/v1/agent/executions?page=${page}&size=5`);
    expect(response.status()).toBe(200);
    const body = (await response.json()).data;
    expect(body.page).toBe(page);
    expect(body.size).toBe(5);
    expect(body.totalElements).toBe(all.totalElements);
    pages.push(...body.content);
  }
  expect(new Set(pages.map((item) => item.executionId)).size).toBe(pages.length);
  expect(pages).toEqual(
    [...pages].sort(
      (a, b) =>
        Date.parse(b.updatedAt) - Date.parse(a.updatedAt) ||
        b.executionId.localeCompare(a.executionId),
    ),
  );
  for (const item of pages) {
    expect(Object.keys(item).sort()).toEqual(
      [
        'executionId',
        'conversationId',
        'knowledgeBaseId',
        'agentMode',
        'status',
        'currentStep',
        'startedAt',
        'updatedAt',
      ].sort(),
    );
  }
  for (const filters of [
    { status: 'SUCCEEDED' },
    { status: 'FAILED' },
    { agentMode: 'REACT' },
    { agentMode: 'PLAN_EXECUTE_REFLECT' },
    { agentMode: 'RETRIEVAL_FIRST' },
    { status: 'SUCCEEDED', agentMode: 'REACT' },
  ]) {
    const params = new URLSearchParams({ page: '0', size: '100' });
    Object.entries(filters).forEach(([key, value]) => params.set(key, value));
    const response = await request.get('/api/v1/agent/executions?' + params);
    expect(response.status()).toBe(200);
    const result = (await response.json()).data;
    for (const item of result.content) expect(item).toMatchObject(filters);
    if (all.totalElements <= 100) {
      expect(result.totalElements).toBe(
        all.content.filter((item: Record<string, string>) =>
          Object.entries(filters).every(([key, value]) => item[key] === value),
        ).length,
      );
    }
  }
  for (const query of ['page=-1', 'size=0', 'size=101', 'status=INVALID', 'agentMode=RAG']) {
    expect((await request.get('/api/v1/agent/executions?' + query)).status()).toBe(400);
  }
  expect((await request.patch('/api/v1/agent/executions')).status()).toBe(405);
  await test.info().attach('execution-pages.json', {
    body: JSON.stringify({ total: all.totalElements, pages }),
    contentType: 'application/json',
  });
});

test('任务页面：真实记录、模式筛选和详情入口', async ({ page }) => {
  await page.goto('/agent-executions');
  await expect(page.getByRole('heading', { name: '任务记录', exact: true })).toBeVisible();
  const view = page.getByRole('link', { name: '查看', exact: true });
  await expect(view.first()).toBeVisible();
  await expect(
    page.getByText('任务列表暂时不可用。已有任务可通过聊天中的任务详情链接打开。'),
  ).toHaveCount(0);
  await page.getByRole('combobox', { name: '筛选任务模式' }).click();
  await page
    .locator('.ant-select-dropdown .ant-select-item-option')
    .filter({ hasText: 'Plan-Execute-Reflect' })
    .click({ timeout: 15_000 });
  await expect(page).toHaveURL(/agentMode=PLAN_EXECUTE_REFLECT/);
  await expect(page.locator('tbody tr.ant-table-row').first()).toContainText(
    'Plan-Execute-Reflect',
  );
  await test
    .info()
    .attach('task-records.png', { body: await page.screenshot(), contentType: 'image/png' });
  await view.first().click();
  await expect(page).toHaveURL(/agent-executions\/[0-9a-f-]+\?knowledgeBaseId=\d+/);
  await expect(page.getByRole('heading', { name: 'Agent 执行详情', exact: true })).toBeVisible();
});

test('系统诊断：五个依赖真实连通状态与刷新', async ({ page, request }) => {
  const response = await request.get('/api/v1/system/health');
  expect(response.status()).toBe(200);
  const body = await response.json();
  expect(body.code).toBe('SUCCESS');
  expect(body.data).toEqual({
    status: 'UP',
    components: {
      database: 'UP',
      redis: 'UP',
      rabbitmq: 'UP',
      qdrant: 'UP',
      elasticsearch: 'UP',
    },
  });
  await page.goto('/system');
  await expect(page.getByText('整体状态', { exact: true })).toBeVisible();
  for (const name of ['MySQL', 'Redis', 'RabbitMQ', 'Qdrant', 'Elasticsearch']) {
    await expect(page.getByText(name, { exact: true })).toBeVisible();
  }
  await expect(page.getByRole('status', { name: '状态：正常' })).toHaveCount(6);
  const refreshed = page.waitForResponse(
    (response) => new URL(response.url()).pathname === '/api/v1/system/health',
  );
  await page.getByRole('button', { name: /刷新健康状态/ }).click();
  expect((await refreshed).status()).toBe(200);
  await test
    .info()
    .attach('system-health.json', { body: JSON.stringify(body), contentType: 'application/json' });
  await test
    .info()
    .attach('system-health.png', { body: await page.screenshot(), contentType: 'image/png' });
});
