import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { ConfigProvider } from 'antd';
import { HttpResponse, http } from 'msw';
import { describe, expect, it } from 'vitest';
import { server } from '../../test/mocks/server';
import { SystemHealthCard } from './SystemHealthCard';

function mount() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false, gcTime: 0 } } });
  return render(
    <QueryClientProvider client={client}>
      <ConfigProvider theme={{ token: { motion: false } }}>
        <SystemHealthCard />
      </ConfigProvider>
    </QueryClientProvider>,
  );
}

describe('real system health contract', () => {
  it('renders the aggregate and five component statuses', async () => {
    mount();
    expect(await screen.findByText('整体状态')).toBeInTheDocument();
    for (const label of ['MySQL', 'Redis', 'RabbitMQ', 'Qdrant', 'Elasticsearch']) {
      expect(screen.getByText(label)).toBeInTheDocument();
    }
    expect(screen.queryByText(/尚未提供/)).not.toBeInTheDocument();
  });

  it('keeps the last successful result when refreshing fails and allows retry', async () => {
    mount();
    await screen.findByText('MySQL');
    server.use(http.get('*/api/v1/system/health', () => new HttpResponse(null, { status: 503 })));
    await userEvent.click(screen.getByRole('button', { name: /刷新健康状态/ }));
    expect(await screen.findByText('健康状态暂时无法获取，请稍后重试。')).toBeInTheDocument();
    expect(screen.getByText('MySQL')).toBeInTheDocument();
    expect(screen.getByText(/上次成功检查/)).toBeInTheDocument();
    server.resetHandlers();
    await userEvent.click(screen.getByRole('button', { name: /刷新健康状态/ }));
    expect(await screen.findByText(/检查于/)).toBeInTheDocument();
    expect(screen.queryByText('健康状态暂时无法获取，请稍后重试。')).not.toBeInTheDocument();
  });
});
