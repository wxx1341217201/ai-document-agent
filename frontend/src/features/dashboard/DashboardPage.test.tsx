import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { configure, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { ConfigProvider } from 'antd';
import { MemoryRouter } from 'react-router-dom';
import { HttpResponse, http } from 'msw';
import { describe, expect, it, vi } from 'vitest';
import { server } from '../../test/mocks/server';
import { getDocuments, getKnowledgeBases } from '../../test/mocks/fixtures';
import { DashboardPage } from './DashboardPage';

configure({ asyncUtilTimeout: 10_000 });

function mount() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false, gcTime: 0 } } });
  return render(
    <QueryClientProvider client={client}>
      <ConfigProvider theme={{ token: { motion: false } }}>
        <MemoryRouter>
          <DashboardPage />
        </MemoryRouter>
      </ConfigProvider>
    </QueryClientProvider>,
  );
}
describe('FE12 dashboard (MSW)', { timeout: 60_000 }, () => {
  it('shows KB/document entry points without loading secondary diagnostics', async () => {
    const diagnostics = vi.fn(() => new HttpResponse(null, { status: 500 }));
    server.use(
      http.get('*/api/v1/agent/executions', diagnostics),
      http.get('*/api/v1/system/health', diagnostics),
    );
    mount();
    expect(await screen.findByRole('link', { name: 'CO₂ 催化研究' })).toBeInTheDocument();
    expect(await screen.findByRole('link', { name: 'catalyst-paper.pdf' })).toBeInTheDocument();
    expect(screen.getByRole('link', { name: '向 CO₂ 催化研究 提问' })).toHaveAttribute(
      'href',
      '/knowledge-bases/1/chat',
    );
    expect(diagnostics).not.toHaveBeenCalled();
    expect(screen.queryByText('MySQL')).not.toBeInTheDocument();
    expect(screen.getByRole('link', { name: '与知识对话' })).toHaveAttribute(
      'href',
      '/knowledge-bases/2/chat',
    );
    expect(screen.getByRole('link', { name: '回到文档原处' })).toHaveAttribute(
      'href',
      '#recent-documents',
    );
    expect(document.getElementById('recent-documents')).toHaveTextContent('最近文档');
  });
  it('stops loading on document failure and lets the user retry without losing KBs', async () => {
    server.use(
      http.get('*/api/v1/dashboard/documents', () => new HttpResponse(null, { status: 503 })),
    );
    mount();
    const retry = await screen.findByRole('button', { name: /重试/ });
    expect(screen.queryByText('正在加载文档…')).not.toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'CO₂ 催化研究' })).toBeInTheDocument();
    server.resetHandlers();
    await userEvent.click(retry);
    expect(await screen.findByRole('link', { name: 'catalyst-paper.pdf' })).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /重试/ })).not.toBeInTheDocument();
  });
  it('loads recent documents independently when the KB list is unavailable', async () => {
    server.use(
      http.get('*/api/v1/dashboard/knowledge-bases', () => new HttpResponse(null, { status: 503 })),
    );
    mount();
    expect(await screen.findByRole('link', { name: 'catalyst-paper.pdf' })).toBeInTheDocument();
    expect(screen.queryByText('正在加载文档…')).not.toBeInTheDocument();
  });
  it('guides the first question to the KB page when no knowledge base exists', async () => {
    server.use(
      http.get('*/api/v1/dashboard/knowledge-bases', () =>
        HttpResponse.json({
          code: 'SUCCESS',
          message: 'OK',
          data: { content: [], page: 0, size: 20, totalElements: 0, totalPages: 0 },
        }),
      ),
      http.get('*/api/v1/dashboard/documents', () =>
        HttpResponse.json({
          code: 'SUCCESS',
          message: 'OK',
          data: {
            content: [],
            page: 0,
            size: 5,
            totalElements: 0,
            totalPages: 0,
          },
        }),
      ),
    );
    mount();
    expect(await screen.findByText('还没有知识库，创建一个开始整理资料。')).toBeInTheDocument();
    expect(await screen.findByText('进入知识库上传第一份文档。')).toBeInTheDocument();
    expect(screen.getByRole('link', { name: '与知识对话' })).toHaveAttribute(
      'href',
      '/knowledge-bases',
    );
    expect(screen.getByRole('link', { name: '整理我的资料' })).toHaveAttribute(
      'href',
      '/knowledge-bases',
    );
  });

  it.each([
    ['knowledge-bases', '知识库'],
    ['documents', '文档'],
  ])(
    'loads the next %s page only near the scroll boundary and stops at the last page',
    async (endpoint, label) => {
      const pages: number[] = [];
      const items = Array.from({ length: 6 }, (_, i) => ({
        ...getKnowledgeBases()[0],
        ...getDocuments(1)[0],
        id: 50 - i,
        name: `资料库${i}`,
        originalName: `资料${i}.txt`,
      }));
      server.use(
        http.get(`*/api/v1/dashboard/${endpoint}`, ({ request }) => {
          const page = Number(new URL(request.url).searchParams.get('page'));
          pages.push(page);
          return HttpResponse.json({
            code: 'SUCCESS',
            message: 'OK',
            data: {
              content: items.slice(page * 5, (page + 1) * 5),
              page,
              size: 5,
              totalElements: 6,
              totalPages: 2,
            },
          });
        }),
      );
      mount();
      const panel = screen.getByRole('region', { name: `最近${label}列表` });
      await within(panel).findByRole('button', { name: `加载更多${label}` });
      expect(pages).toEqual([0]);
      Object.defineProperties(panel, {
        scrollHeight: { configurable: true, value: 600 },
        clientHeight: { configurable: true, value: 300 },
        scrollTop: { configurable: true, writable: true, value: 0 },
      });
      fireEvent.wheel(panel, { deltaY: -100 });
      fireEvent.wheel(panel, { deltaY: 100 });
      expect(pages).toEqual([0]);
      panel.scrollTop = 300;
      fireEvent.scroll(panel);
      await within(panel).findByText(`已显示全部6条${label}`);
      expect(pages).toEqual([0, 1]);
      expect(
        within(panel).getByRole('link', {
          name: endpoint === 'documents' ? '资料5.txt' : '资料库5',
        }),
      ).toBeInTheDocument();
      fireEvent.wheel(panel, { deltaY: 100 });
      expect(pages).toEqual([0, 1]);
    },
  );

  it('keeps loaded documents on a next-page failure, then retries that page once', async () => {
    const pages: number[] = [];
    let failNext = true;
    server.use(
      http.get('*/api/v1/dashboard/documents', ({ request }) => {
        const page = Number(new URL(request.url).searchParams.get('page'));
        pages.push(page);
        if (page === 1 && failNext) return new HttpResponse(null, { status: 503 });
        return HttpResponse.json({
          code: 'SUCCESS',
          message: 'OK',
          data: {
            content: [
              { ...getDocuments(1)[0], id: 100 - page, originalName: `第${page + 1}页.txt` },
            ],
            page,
            size: 5,
            totalElements: 6,
            totalPages: 2,
          },
        });
      }),
    );
    mount();
    const panel = screen.getByRole('region', { name: '最近文档列表' });
    await within(panel).findByRole('link', { name: '第1页.txt' });
    fireEvent.wheel(panel, { deltaY: 100 });
    await within(panel).findByText('更多文档加载失败');
    expect(within(panel).getByRole('link', { name: '第1页.txt' })).toBeInTheDocument();
    fireEvent.wheel(panel, { deltaY: 100 });
    expect(pages).toEqual([0, 1]);
    failNext = false;
    await userEvent.click(within(panel).getByRole('button', { name: /重试/ }));
    await within(panel).findByRole('link', { name: '第2页.txt' });
    await waitFor(() =>
      expect(within(panel).queryByText('更多文档加载失败')).not.toBeInTheDocument(),
    );
    expect(pages).toEqual([0, 1, 1]);
  });
});
