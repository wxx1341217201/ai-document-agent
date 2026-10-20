import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { ConfigProvider } from 'antd';
import { HttpResponse, http } from 'msw';
import { MemoryRouter, useLocation } from 'react-router-dom';
import { describe, expect, it, vi } from 'vitest';
import { mockChatCitation } from '../../test/mocks/conversation-fixtures';
import { server } from '../../test/mocks/server';
import { CitationDrawer } from './CitationDrawer';

function Location() {
  const location = useLocation();
  return <output aria-label="目标地址">{location.pathname + location.search}</output>;
}
function mount() {
  const close = vi.fn();
  const client = new QueryClient({ defaultOptions: { queries: { retry: false, gcTime: 0 } } });
  const view = render(
    <QueryClientProvider client={client}>
      <ConfigProvider theme={{ token: { motion: false } }}>
        <MemoryRouter>
          <Location />
          <CitationDrawer
            knowledgeBaseId={1}
            citation={{
              ...mockChatCitation,
              pageFrom: 2,
              pageTo: 2,
              documentName: '<img src=x onerror=alert(1)>',
            }}
            onClose={close}
          />
        </MemoryRouter>
      </ConfigProvider>
    </QueryClientProvider>,
  );
  return { ...view, close };
}
describe('FE11 citation context (explicit Mock response)', { timeout: 60_000 }, () => {
  it('shows supplied quote, server-returned neighbors and a validated PDF page deep link without HTML execution', async () => {
    const { close } = mount();
    expect(screen.queryByText('Mock 后文：恢复时核对服务端状态。')).not.toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: '更多上下文' }));
    expect(await screen.findByText('Mock 后文：恢复时核对服务端状态。')).toBeInTheDocument();
    expect(screen.getByText('<img src=x onerror=alert(1)>')).toBeInTheDocument();
    expect(document.querySelector('img[onerror]')).toBeNull();
    fireEvent.click(screen.getByRole('button', { name: '查看文档' }));
    expect(screen.getByLabelText('目标地址')).toHaveTextContent(
      '/knowledge-bases/1/documents/101?page=2&chunkId=1001',
    );
    expect(close).toHaveBeenCalledOnce();
  });
  it('supports context error/retry and empty results without replacing the citation quote', async () => {
    let failed = true;
    server.use(
      http.get('*/api/v1/knowledge-bases/1/documents/101/chunks/1001/context', () =>
        failed
          ? HttpResponse.json(
              { code: 'NOT_FOUND', message: '上下文尚未提供', data: null },
              { status: 404 },
            )
          : HttpResponse.json({ code: 'SUCCESS', message: 'OK', data: [] }),
      ),
    );
    mount();
    fireEvent.click(screen.getByRole('button', { name: '更多上下文' }));
    expect(await screen.findByText('上下文尚未提供')).toBeInTheDocument();
    expect(screen.getByText(mockChatCitation.quote)).toBeInTheDocument();
    failed = false;
    fireEvent.click(screen.getByRole('button', { name: /重试/ }));
    await waitFor(() => expect(screen.getByText('服务端未返回相邻片段。')).toBeInTheDocument());
  });
});
