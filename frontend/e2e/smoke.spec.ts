import { expect, test } from '@playwright/test';

test('知识库和文档处理主流程可访问', async ({ page }) => {
  await page.goto('/knowledge-bases');

  await expect(page.getByRole('heading', { name: '知识库' })).toBeVisible();
  await page.getByRole('link', { name: 'CO₂ 催化研究' }).click();

  await expect(page.getByRole('heading', { name: 'CO₂ 催化研究' })).toBeVisible();
  await expect(page.getByRole('link', { name: 'catalyst-paper.pdf' })).toBeVisible();

  await page.locator('input[type="file"]').setInputFiles({
    name: 'notes.txt',
    mimeType: 'text/plain',
    buffer: Buffer.from('A mocked document for the upload workflow.'),
  });
  await expect(page.getByRole('link', { name: 'notes.txt' })).toBeVisible();

  await page.getByRole('link', { name: 'failed-experiment.docx' }).click();
  await expect(page.getByRole('button', { name: '重新处理' })).toBeVisible();
  await page.getByRole('button', { name: '重新处理' }).click();
  await expect(page.getByRole('status', { name: '状态：重试中' })).toBeVisible();
});
