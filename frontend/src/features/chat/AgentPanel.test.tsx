import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { App, ConfigProvider } from 'antd';
import { HttpResponse, http } from 'msw';
import { describe, expect, it, vi } from 'vitest';
import type { ComponentProps } from 'react';
import type { Conversation, ConversationMessage } from '../../api/contracts';
import { completeMockPlan, mockPlanExecution } from '../../test/mocks/agent-panel-fixtures';
import { server } from '../../test/mocks/server';
import { AgentPanel } from './AgentPanel';

const conversation: Conversation = {
  id: 303,
  knowledgeBaseId: 1,
  title: '可视化测试',
  agentMode: 'PLAN_EXECUTE_REFLECT',
  createdAt: '2026-10-11T08:30:00',
  updatedAt: '2026-10-11T08:30:00',
};

function renderPanel(overrides: Partial<ComponentProps<typeof AgentPanel>> = {}) {
  const props: ComponentProps<typeof AgentPanel> = {
    conversation,
    executions: [],
    submitting: false,
    onSelectExecution: vi.fn(),
    onCitationClick: vi.fn(),
    ...overrides,
  };
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false, gcTime: 0 } } });
  const view = render(
    <QueryClientProvider client={queryClient}>
      <ConfigProvider theme={{ token: { motion: false } }}>
        <App>
          <AgentPanel {...props} />
        </App>
      </ConfigProvider>
    </QueryClientProvider>,
  );
  return { ...view, props, queryClient };
}

const success = (data: unknown) => HttpResponse.json({ code: 'SUCCESS', message: 'OK', data });

describe('AgentPanel (MSW UI integration)', { timeout: 60_000 }, () => {
  it('has honest empty and submission loading states without generated steps', () => {
    const { rerender, props } = renderPanel();
    expect(screen.getByText('提交问题后，在这里查看执行步骤与工具调用。')).toBeInTheDocument();
    rerender(<AgentPanel {...props} submitting />);
    expect(screen.getByRole('status')).toHaveTextContent('正在提交执行任务…');
    expect(screen.queryByLabelText('执行步骤')).not.toBeInTheDocument();
  });

  it('shows scoped REST steps, dependencies and tool summaries and reports transport degradation', async () => {
    const execution = mockPlanExecution();
    server.use(http.get('*/api/v1/agent/executions/:id', () => success(execution)));
    const { container } = renderPanel({ executions: [{ execution, question: '比较重试策略' }] });
    const steps = screen.getByLabelText('执行步骤');
    expect(within(steps).getByText('Plan / Step Timeline')).toBeInTheDocument();
    expect(within(steps).getByText('依赖：r0_S1')).toBeInTheDocument();
    expect(container.querySelector('[data-step-id="r0_S2"]')).toHaveClass('agent-step-current');
    const tools = screen.getByLabelText('工具调用摘要');
    expect(within(tools).getByText('searchKnowledgeBase')).toBeInTheDocument();
    expect(within(tools).getByText('320 ms')).toBeInTheDocument();
    expect(within(tools).getByText('检索词长度')).toBeInTheDocument();
    expect(await screen.findByText('实时进度已降级')).toBeInTheDocument();
    expect(container.querySelector('.agent-running-dot')).not.toBeNull();
  });

  it('keeps the last snapshot on GET failure and retries only the scoped detail request', async () => {
    const execution = mockPlanExecution();
    let unavailable = true;
    let posts = 0;
    const knowledgeBases: string[] = [];
    server.use(
      http.post('*/api/v1/agent/executions', () => {
        posts += 1;
        return HttpResponse.error();
      }),
      http.get('*/api/v1/agent/executions/:id', ({ request }) => {
        knowledgeBases.push(new URL(request.url).searchParams.get('knowledgeBaseId') ?? '');
        return unavailable
          ? HttpResponse.json(
              { code: 'UNAVAILABLE', message: '详情读取失败', data: null, traceId: 'fe09-read' },
              { status: 503 },
            )
          : success(completeMockPlan(execution));
      }),
    );
    renderPanel({ executions: [{ execution, question: '问题' }] });
    fireEvent.click(screen.getByRole('button', { name: '刷新 Agent 详情' }));
    expect(await screen.findByText('详情读取失败')).toBeInTheDocument();
    expect(screen.getByText('依赖：r0_S1')).toBeInTheDocument();
    unavailable = false;
    fireEvent.click(screen.getByRole('button', { name: '重试加载 Agent 详情' }));
    expect(await screen.findByText('证据覆盖检查通过')).toBeInTheDocument();
    await waitFor(() => expect(screen.queryByText('详情读取失败')).not.toBeInTheDocument());
    expect(knowledgeBases).toEqual(['1', '1']);
    expect(posts).toBe(0);
  });

  it.each([true, false])(
    'displays retryable=%s failure without offering a resume/create action',
    (retryable) => {
      const execution = {
        ...mockPlanExecution(),
        status: 'FAILED' as const,
        retryable,
        errorCode: 'TOOL_TIMEOUT',
      };
      const { container } = renderPanel({ executions: [{ execution, question: '问题' }] });
      expect(
        screen.getByText(retryable ? '执行失败 · 可重试' : '执行失败 · 当前不可重试'),
      ).toBeInTheDocument();
      expect(screen.getByText('错误码：TOOL_TIMEOUT')).toBeInTheDocument();
      expect(
        screen.queryByRole('button', { name: /Resume|恢复任务|重试任务|Cancel/ }),
      ).not.toBeInTheDocument();
      expect(container.querySelector('.agent-running-dot')).toBeNull();
    },
  );

  it.each(['PAUSED', 'CANCELLED'] as const)(
    'preserves %s without showing an active running animation',
    (status) => {
      const execution = { ...mockPlanExecution(), status };
      server.use(http.get('*/api/v1/agent/executions/:id', () => success(execution)));
      const { container } = renderPanel({ executions: [{ execution, question: '问题' }] });
      expect(
        screen.getByText(status === 'PAUSED' ? '任务已暂停，等待恢复' : '任务已取消'),
      ).toBeInTheDocument();
      expect(container.querySelector('.agent-running-dot')).toBeNull();
    },
  );

  it('renders final references as text, opens the supplied reference and hides arbitrary reasoning/HTML', () => {
    const source = completeMockPlan(mockPlanExecution());
    const result = source.result as Record<string, unknown> & {
      citations: Record<string, unknown>[];
    };
    const execution = {
      ...source,
      result: {
        ...result,
        thoughts: 'hidden model reasoning',
        citations: [{ ...result.citations[0], documentName: '<img src=x onerror=alert(1)>' }],
      },
      toolCalls: source.toolCalls.map((tool) => ({
        ...tool,
        argumentsSummary: {
          ...(tool.argumentsSummary as object),
          chainOfThought: 'hidden tool reasoning',
          query: '<think>private</think>',
        },
      })),
    };
    const { container, props } = renderPanel({
      executions: [{ execution, question: '<script>alert(1)</script>' }],
    });
    expect(screen.getByText('执行已完成')).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: '[C1] <img src=x onerror=alert(1)>' }));
    expect(props.onCitationClick).toHaveBeenCalledWith(
      expect.objectContaining({ citationId: 'C1', documentId: 101, chunkId: 1001 }),
    );
    expect(container.querySelector('script, img[onerror]')).toBeNull();
    expect(container.textContent).not.toMatch(/hidden model|hidden tool|private/);
  });

  it('honors selection of an older completed task', () => {
    const first = completeMockPlan(mockPlanExecution());
    const second = {
      ...completeMockPlan(mockPlanExecution()),
      errorCode: 'EXECUTION_FAILED',
      status: 'FAILED' as const,
    };
    const executions = [
      { execution: first, question: '第一轮问题' },
      { execution: second, question: '第二轮问题' },
    ];
    const { container } = renderPanel({ executions, selectedExecutionId: first.executionId });
    expect(screen.getByRole('combobox', { name: '选择 Agent 执行' })).toBeInTheDocument();
    expect(container.querySelector('.agent-execution-details')).toHaveAttribute(
      'data-execution-id',
      first.executionId,
    );
    expect(screen.getByText('第一轮问题', { exact: false })).toBeInTheDocument();
    expect(screen.queryByText('执行失败 · 当前不可重试')).not.toBeInTheDocument();
  });

  it('shows synchronous success and citations without pretending to have a Retrieval-First trace', () => {
    const message = {
      id: 1,
      conversationId: 303,
      sequenceNo: 2,
      role: 'ASSISTANT',
      content: '回答',
      citations: [],
      modelName: null,
      tokenUsage: null,
      createdAt: conversation.updatedAt,
    } as ConversationMessage;
    renderPanel({
      conversation: { ...conversation, agentMode: 'RETRIEVAL_FIRST' },
      latestAssistantMessage: message,
    });
    expect(screen.getByText('本轮回答已完成')).toBeInTheDocument();
    expect(
      screen.getByText('同步消息接口未提供步骤与工具摘要，回答及引用显示在消息历史中。'),
    ).toBeInTheDocument();
    expect(screen.queryByLabelText('执行步骤')).not.toBeInTheDocument();
  });
});
