import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { ConfigProvider } from 'antd';
import { HttpResponse, http } from 'msw';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { describe, expect, it } from 'vitest';
import { mockPlanExecution } from '../../test/mocks/agent-panel-fixtures';
import { server } from '../../test/mocks/server';
import { ExecutionDetailPage } from './ExecutionDetailPage';
import { ExecutionListPage } from './ExecutionListPage';

const success = (data: unknown) => HttpResponse.json({ code: 'SUCCESS', message: 'OK', data });
function mount(path: string) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false, gcTime: 0 } } });
  return render(
    <QueryClientProvider client={client}>
      <ConfigProvider theme={{ token: { motion: false } }}>
        <MemoryRouter initialEntries={[path]}>
          <Routes>
            <Route path="/agent-executions" element={<ExecutionListPage />} />
            <Route path="/agent-executions/:executionId" element={<ExecutionDetailPage />} />
          </Routes>
        </MemoryRouter>
      </ConfigProvider>
    </QueryClientProvider>,
  );
}

describe('FE10 task recovery (MSW)', { timeout: 60_000 }, () => {
  it('recovers a paused task directly from the URL and reconciles concurrent resume 409 without retrying POST', async () => {
    let execution = { ...mockPlanExecution(), status: 'PAUSED' as 'PAUSED' | 'RUNNING' };
    let posts = 0;
    let gets = 0;
    server.use(
      http.get('*/api/v1/agent/executions/:id', ({ request }) => {
        expect(new URL(request.url).searchParams.get('knowledgeBaseId')).toBe('1');
        gets += 1;
        return success(execution);
      }),
      http.post('*/api/v1/agent/executions/:id/resume', () => {
        posts += 1;
        execution = { ...execution, status: 'RUNNING' };
        return HttpResponse.json(
          { code: 'STATE_CONFLICT', message: '执行状态不允许此操作', data: null },
          { status: 409 },
        );
      }),
    );
    mount(`/agent-executions/${execution.executionId}?knowledgeBaseId=1`);
    const resume = await screen.findByRole('button', { name: 'Resume · 恢复任务' });
    expect(resume).toBeEnabled();
    fireEvent.click(resume);
    fireEvent.click(resume);
    expect(await screen.findByText('任务状态已变化，或另一请求已恢复任务')).toBeInTheDocument();
    await waitFor(() => expect(resume).toBeDisabled());
    expect(posts).toBe(1);
    expect(gets).toBeGreaterThan(1);
    expect(screen.getByRole('button', { name: 'Cancel · 取消任务' })).toBeEnabled();
  });
  it('does not issue requests for invalid UUID/scope', () => {
    mount('/agent-executions/not-a-uuid?knowledgeBaseId=-1');
    expect(screen.getByText('任务地址无效')).toBeInTheDocument();
  });
  it('lists server tasks with scope in links', async () => {
    mount('/agent-executions?status=PAUSED');
    const view = await screen.findByRole('link', { name: '查看' });
    expect(view).toHaveAttribute(
      'href',
      '/agent-executions/11111111-1111-4111-8111-111111111111?knowledgeBaseId=1',
    );
  });
  it('reports a missing list API and retains the known-task entry form', async () => {
    server.use(
      http.get('*/api/v1/agent/executions', () =>
        HttpResponse.json(
          { code: 'NOT_FOUND', message: '列表尚未提供', data: null },
          { status: 404 },
        ),
      ),
    );
    mount('/agent-executions');
    expect(await screen.findByText('列表尚未提供')).toBeInTheDocument();
    fireEvent.click(screen.getByText('按任务编号打开（高级）'));
    expect(screen.getByRole('button', { name: '打开任务' })).toBeEnabled();
  });
});
