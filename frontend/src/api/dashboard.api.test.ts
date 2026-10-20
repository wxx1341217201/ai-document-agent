import { describe, expect, it, vi } from 'vitest';
import { HttpResponse, http } from 'msw';
import { dashboardApi } from './dashboard.api';
import { getDocuments, getKnowledgeBases } from '../test/mocks/fixtures';
import { server } from '../test/mocks/server';

describe('dashboard paginated summary APIs', () => {
  it('reads only the requested KB page with an explicit page size', async () => {
    const pages: number[] = [];
    server.use(
      http.get('*/api/v1/dashboard/knowledge-bases', ({ request }) => {
        const search = new URL(request.url).searchParams;
        expect([...search.keys()]).toEqual(['page', 'size']);
        expect(search.get('size')).toBe('5');
        const page = Number(search.get('page'));
        pages.push(page);
        return HttpResponse.json({
          code: 'SUCCESS',
          message: 'OK',
          data: {
            content: [{ ...getKnowledgeBases()[0], id: 10 - page }],
            page,
            size: 5,
            totalElements: 6,
            totalPages: 2,
          },
        });
      }),
    );
    expect((await dashboardApi.knowledgeBases({ page: 0, size: 5 })).content[0].id).toBe(10);
    expect(pages).toEqual([0]);
    expect((await dashboardApi.knowledgeBases({ page: 1, size: 5 })).content[0].id).toBe(9);
    expect(pages).toEqual([0, 1]);
  });

  it('uses one bounded document page instead of scanning every KB', async () => {
    const records = Array.from({ length: 5 }, (_, index) => ({
      ...getDocuments(1)[0],
      id: 1851 - index,
    }));
    const recent = vi.fn(() =>
      HttpResponse.json({
        code: 'SUCCESS',
        message: 'OK',
        data: {
          content: records,
          page: 0,
          size: 5,
          totalElements: 1844,
          totalPages: 369,
        },
      }),
    );
    const legacy = vi.fn(() => new HttpResponse(null, { status: 500 }));
    server.use(
      http.get('*/api/v1/dashboard/documents', recent),
      http.get('*/api/v1/documents/recent', legacy),
      http.get('*/api/v1/knowledge-bases/:knowledgeBaseId/documents', legacy),
    );
    expect((await dashboardApi.documents({ page: 0, size: 5 })).content).toEqual(records);
    expect(recent).toHaveBeenCalledTimes(1);
    expect(legacy).not.toHaveBeenCalled();
  });

  it('reports a failed page so the list can offer a retry', async () => {
    server.use(
      http.get('*/api/v1/dashboard/documents', () => new HttpResponse(null, { status: 503 })),
    );
    await expect(dashboardApi.documents({ page: 1, size: 5 })).rejects.toMatchObject({
      httpStatus: 503,
    });
  });
});
