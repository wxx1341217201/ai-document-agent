import { describe, expect, it } from 'vitest';
import { HttpResponse, http } from 'msw';
import { documentPreviewApi, parseMockCitationContext } from './document-preview.api';
import { server } from '../test/mocks/server';

describe('FE11 planned document APIs (MSW, not live backend)', () => {
  it('reads documented chunk pagination and sends an anchor ID without guessing adjacent IDs', async () => {
    const page = await documentPreviewApi.chunks(1, 101, { page: 0, size: 50 });
    expect(page.totalElements).toBe(3);
    let query = '';
    server.use(
      http.get('*/api/v1/knowledge-bases/1/documents/101/chunks/1001/context', ({ request }) => {
        query = new URL(request.url).search;
        return HttpResponse.json({ code: 'SUCCESS', message: 'OK', data: page.content });
      }),
    );
    const context = parseMockCitationContext(await documentPreviewApi.context(1, 101, 1001));
    expect(context.map((chunk) => chunk.chunkId)).toEqual([997, 1001, 1059]);
    expect(query).toBe('?before=1&after=1');
    await expect(documentPreviewApi.chunks(2, 101, { page: 0, size: 50 })).rejects.toMatchObject({
      httpStatus: 404,
    });
  });
  it('rejects HTML or JSON returned as a PDF, even when mislabeled', async () => {
    server.use(
      http.get(
        '*/api/v1/knowledge-bases/1/documents/101/download',
        () =>
          new HttpResponse('<html><script>alert(1)</script></html>', {
            headers: { 'Content-Type': 'application/pdf' },
          }),
      ),
    );
    await expect(documentPreviewApi.pdf(1, 101)).rejects.toMatchObject({ code: 'INVALID_PDF' });
  });
  it('verifies PDF bytes and gives them a fixed PDF MIME', async () => {
    const pdf = await documentPreviewApi.pdf(1, 101);
    expect(pdf.type).toBe('application/pdf');
    expect(pdf.size).toBeGreaterThan(100);
  });
  it('rejects malformed chunk records rather than rendering arbitrary structures', () => {
    expect(() => parseMockCitationContext([{ chunkId: 1, content: '<script>' }])).toThrow();
  });
});
