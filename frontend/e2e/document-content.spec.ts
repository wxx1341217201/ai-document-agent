import { createHash } from 'node:crypto';
import { readFile } from 'node:fs/promises';
import { expect, test } from '@playwright/test';

test.setTimeout(90_000);

test('文档正文：真实片段分页、顺序、边界与归属检查', async ({ request }) => {
  const readPage = async (page: number) => {
    const response = await request.get(
      `/api/v1/knowledge-bases/8/documents/15/chunks?page=${page}&size=50`,
    );
    expect(response.status()).toBe(200);
    const body = await response.json();
    expect(body.code).toBe('SUCCESS');
    expect(body.data.page).toBe(page);
    expect(body.data.size).toBe(50);
    expect(body.data.totalElements).toBe(1125);
    return body.data;
  };
  const first = await readPage(0);
  const second = await readPage(1);
  const last = await readPage(22);
  const beyond = await readPage(23);
  expect(first.content).toHaveLength(50);
  expect(second.content).toHaveLength(50);
  expect(last.content).toHaveLength(25);
  expect(beyond.content).toEqual([]);
  const chunks = [...first.content, ...second.content];
  expect(chunks.map((chunk) => chunk.chunkIndex)).toEqual(
    Array.from({ length: 100 }, (_, index) => index),
  );
  expect(new Set(chunks.map((chunk) => chunk.chunkId)).size).toBe(100);
  for (const chunk of chunks) {
    expect(Object.keys(chunk).sort()).toEqual(
      ['chunkId', 'chunkIndex', 'content', 'pageFrom', 'pageTo', 'sectionTitle'].sort(),
    );
    expect(chunk.content.length).toBeGreaterThan(0);
  }
  for (const query of ['page=-1', 'size=0', 'size=101']) {
    expect(
      (await request.get(`/api/v1/knowledge-bases/8/documents/15/chunks?${query}`)).status(),
    ).toBe(400);
  }
  for (const endpoint of ['chunks', 'download']) {
    const response = await request.get(`/api/v1/knowledge-bases/8/documents/1851/${endpoint}`, {
      headers: { Accept: 'application/octet-stream,application/pdf' },
    });
    expect(response.status()).toBe(404);
    expect((await response.json()).code).toBe('DOCUMENT_NOT_FOUND');
    expect(
      (await request.get(`/api/v1/knowledge-bases/10/documents/2147483647/${endpoint}`)).status(),
    ).toBe(404);
  }
  await test
    .info()
    .attach('document-pages.json', {
      body: JSON.stringify({
        first: first.content.map((c: { chunkId: number }) => c.chunkId),
        second: second.content.map((c: { chunkId: number }) => c.chunkId),
        total: first.totalElements,
      }),
      contentType: 'application/json',
    });
});

test('原文件：TXT、PDF、DOCX 下载的类型、大小及 SHA-256 与元数据一致', async ({ request }) => {
  for (const [kbId, id, type] of [
    [10, 1851, 'text/plain'],
    [8, 15, 'text/plain'],
    [2, 3, 'application/pdf'],
    [2, 4, 'application/vnd.openxmlformats-officedocument.wordprocessingml.document'],
  ] as const) {
    const metadataResponse = await request.get(`/api/v1/knowledge-bases/${kbId}/documents/${id}`);
    expect(metadataResponse.status()).toBe(200);
    const metadata = (await metadataResponse.json()).data;
    const response = await request.get(`/api/v1/knowledge-bases/${kbId}/documents/${id}/download`, {
      headers: { Accept: 'application/octet-stream,application/pdf' },
    });
    expect(response.status()).toBe(200);
    expect(response.headers()['content-type']).toContain(type);
    expect(response.headers()['content-disposition']).toContain('attachment;');
    expect(response.headers()['content-disposition']).toContain("filename*=UTF-8''");
    expect(response.headers()['x-content-type-options']).toBe('nosniff');
    expect(response.headers()['cache-control']).toContain('no-store');
    const bytes = await response.body();
    expect(bytes.length).toBe(metadata.sizeBytes);
    expect(createHash('sha256').update(bytes).digest('hex')).toBe(metadata.sha256);
    if (type === 'application/pdf') expect(bytes.subarray(0, 5).toString()).toBe('%PDF-');
  }
});

test('文档页面：截图中的正文可见、原文件可下载、原书正文可翻页', async ({ page, request }) => {
  const metadata = (await (await request.get('/api/v1/knowledge-bases/10/documents/1851')).json())
    .data;
  const chunks = (
    await (
      await request.get('/api/v1/knowledge-bases/10/documents/1851/chunks?page=0&size=50')
    ).json()
  ).data;
  await page.goto('/knowledge-bases/10/documents/1851');
  await expect(page.locator('.document-chunk')).toHaveCount(1);
  await expect(page.locator('.document-extracted-text')).toHaveText(chunks.content[0].content);
  await expect(page.locator('.ant-alert-error')).toHaveCount(0);
  const downloadEvent = page.waitForEvent('download');
  await page.getByRole('button', { name: /下载原文件/ }).click();
  const download = await downloadEvent;
  expect(download.suggestedFilename()).toBe(metadata.originalName);
  const bytes = await readFile((await download.path())!);
  expect(createHash('sha256').update(bytes).digest('hex')).toBe(metadata.sha256);
  await test
    .info()
    .attach('document-content.png', { body: await page.screenshot(), contentType: 'image/png' });
  await page.goto('/knowledge-bases/8/documents/15');
  await expect(page.locator('.document-chunk')).toHaveCount(50);
  await expect(page.locator('.document-chunk').first()).toContainText('片段 1');
  await page.locator('.ant-pagination-item-2 a').click();
  await expect(page.locator('.document-chunk').first()).toContainText('片段 51');
});
