import { StrictMode } from 'react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { act, configure, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { App, ConfigProvider } from 'antd';
import { HttpResponse, http } from 'msw';
import { MemoryRouter, Route, Routes, useLocation } from 'react-router-dom';
import { describe, expect, it } from 'vitest';
import type { Conversation } from '../../api/contracts';
import { queryKeys } from '../../api/query-keys';
import {
  addConversation,
  addConversationTurn,
  getConversation,
  getConversationMessages,
} from '../../test/mocks/conversation-fixtures';
import { completeMockExecution, mockExecution } from '../../test/mocks/execution-fixtures';
import { server } from '../../test/mocks/server';
import { ChatPage } from './ChatPage';

// Loading the KB, paged conversation list and message history involves multiple async renders.
configure({ asyncUtilTimeout: 10_000 });

function Location() {
  return <output aria-label="当前地址">{useLocation().pathname}</output>;
}

function renderChat(path = '/knowledge-bases/1/chat/301', strict = false) {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  });
  const Wrapper = strict ? StrictMode : ({ children }: { children: React.ReactNode }) => children;
  const result = render(
    <Wrapper>
      <QueryClientProvider client={queryClient}>
        <ConfigProvider theme={{ token: { motion: false } }}>
          <App>
            <MemoryRouter initialEntries={[path]}>
              <Routes>
                <Route path="/knowledge-bases/:kbId/chat/:conversationId?" element={<ChatPage />} />
                <Route
                  path="/knowledge-bases/:kbId/documents/:documentId"
                  element={<div>引用文档页</div>}
                />
              </Routes>
              <Location />
            </MemoryRouter>
          </App>
        </ConfigProvider>
      </QueryClientProvider>
    </Wrapper>,
  );
  return { ...result, queryClient };
}

function success(data: unknown) {
  return HttpResponse.json({ code: 'SUCCESS', message: 'OK', data, traceId: 'chat-test' });
}

describe('ChatPage (MSW integration tests)', { timeout: 60_000 }, () => {
  it.each(['REACT', 'PLAN_EXECUTE_REFLECT'] as const)(
    'binds %s to the pending assistant, keeps it across navigation, then refreshes its real answer',
    async (agentMode) => {
      const conversation = addConversation(1, { title: '执行关联测试', agentMode });
      const execution = mockExecution({
        knowledgeBaseId: 1,
        conversationId: conversation.id,
        agentMode,
        question: '执行问题',
      });
      let release!: () => void;
      const gate = new Promise<void>((resolve) => {
        release = resolve;
      });
      let posts = 0;
      let gets = 0;
      let completed = false;
      let key: string | null = null;
      server.use(
        http.post('*/api/v1/agent/executions', async ({ request }) => {
          posts += 1;
          key = request.headers.get('Idempotency-Key');
          expect(await request.json()).toEqual({
            knowledgeBaseId: 1,
            conversationId: conversation.id,
            agentMode,
            question: '执行问题',
          });
          await gate;
          return success(execution);
        }),
        http.get('*/api/v1/agent/executions/:id', ({ request, params }) => {
          gets += 1;
          expect(params.id).toBe(execution.executionId);
          expect(new URL(request.url).searchParams.get('knowledgeBaseId')).toBe('1');
          return success(completed ? completeMockExecution(execution) : execution);
        }),
      );
      const { queryClient } = renderChat('/knowledge-bases/1/chat/' + conversation.id);
      const input = await screen.findByLabelText('消息');
      await waitFor(() => expect(input).toBeEnabled());
      fireEvent.change(input, { target: { value: '执行问题' } });
      fireEvent.keyDown(input, { key: 'Enter' });
      fireEvent.keyDown(input, { key: 'Enter' });
      let assistantMessageId: string | null = null;
      try {
        const pending = await screen.findByLabelText('待回复的 AI 消息');
        assistantMessageId = pending.getAttribute('data-assistant-message-id');
        expect(assistantMessageId).toBeTruthy();
        await waitFor(() => expect(posts).toBe(1));
        expect(assistantMessageId).toBe('assistant-' + key);
      } finally {
        await act(async () => release());
      }
      const reply = await screen.findByLabelText('Agent 执行回复');
      expect(reply).toHaveAttribute('data-execution-id', execution.executionId);
      expect(reply).toHaveAttribute('data-assistant-message-id', assistantMessageId);
      expect(input).toBeDisabled();
      expect(screen.getByRole('button', { name: '删除会话 执行关联测试' })).toBeDisabled();
      expect(screen.getByText('任务已提交')).toBeInTheDocument();
      expect(screen.queryByText('消息已保存')).not.toBeInTheDocument();
      expect(queryClient.getQueryData(queryKeys.messages.list(conversation.id))).toEqual([]);
      fireEvent.click(screen.getByRole('button', { name: '打开会话 旧版 RAG 会话' }));
      expect(await screen.findByText('还没有消息，发送第一个问题开始对话。')).toBeInTheDocument();
      expect(screen.queryByLabelText('Agent 执行回复')).not.toBeInTheDocument();
      fireEvent.click(screen.getByRole('button', { name: '打开会话 执行关联测试' }));
      expect(await screen.findByLabelText('Agent 执行回复')).toHaveAttribute(
        'data-assistant-message-id',
        assistantMessageId,
      );
      expect(screen.getByLabelText('消息')).toBeDisabled();
      fireEvent.click(screen.getByText('任务信息'));
      const refreshButton = screen.getByRole('button', { name: '刷新执行状态' });
      await waitFor(() => expect(refreshButton).not.toHaveClass('ant-btn-loading'));
      const readsBeforeRefresh = gets;
      completed = true;
      fireEvent.click(refreshButton);
      expect(await screen.findByText('Mock 执行回答')).toBeInTheDocument();
      await waitFor(() => expect(screen.getByLabelText('消息')).toBeEnabled());
      expect(posts).toBe(1);
      expect(gets).toBeGreaterThan(readsBeforeRefresh);
      expect(getConversationMessages(conversation.id)).toEqual([]);
      expect(queryClient.getQueryData(queryKeys.messages.list(conversation.id))).toEqual([]);
      fireEvent.click(screen.getByRole('button', { name: '[C1] catalyst-paper.pdf' }));
      const citation = await screen.findByRole('dialog', { name: '[C1] 引用详情' });
      fireEvent.click(within(citation).getByRole('button', { name: '技术信息' }));
      expect(within(citation).getByText('1001')).toBeInTheDocument();
    },
  );

  it('restores the failed draft without an automatic retry and reuses the key for explicit retry', async () => {
    const conversation = addConversation(1, { agentMode: 'REACT' });
    let posts = 0;
    let fallbackPosts = 0;
    const keys: (string | null)[] = [];
    const execution = mockExecution({
      knowledgeBaseId: 1,
      conversationId: conversation.id,
      agentMode: 'REACT',
      question: '恢复草稿',
    });
    server.use(
      http.post('*/api/v1/agent/executions', ({ request }) => {
        posts += 1;
        keys.push(request.headers.get('Idempotency-Key'));
        if (posts > 1) return success(execution);
        return HttpResponse.json(
          {
            code: 'STATE_CONFLICT',
            message: '执行暂不可用',
            data: null,
            traceId: 'execution-create-error',
          },
          { status: 409 },
        );
      }),
      http.post('*/api/v1/conversations/:id/messages', () => {
        fallbackPosts += 1;
        return HttpResponse.error();
      }),
    );
    renderChat('/knowledge-bases/1/chat/' + conversation.id);
    const input = await screen.findByLabelText('消息');
    await waitFor(() => expect(input).toBeEnabled());
    fireEvent.change(input, { target: { value: '恢复草稿' } });
    fireEvent.click(screen.getByRole('button', { name: '发送' }));
    expect(await screen.findByText('执行暂不可用')).toBeInTheDocument();
    await waitFor(() => expect(input).toHaveValue('恢复草稿'));
    expect(input).toBeEnabled();
    expect(screen.queryByLabelText('待回复的 AI 消息')).not.toBeInTheDocument();
    expect(posts).toBe(1);
    expect(fallbackPosts).toBe(0);
    fireEvent.click(screen.getByRole('button', { name: '发送' }));
    await screen.findByLabelText('Agent 执行回复');
    expect(posts).toBe(2);
    expect(keys[1]).toBe(keys[0]);
    expect(fallbackPosts).toBe(0);
  });

  it('keeps the associated pending task after GET fails and retries only the scoped GET', async () => {
    const conversation = addConversation(1, { agentMode: 'REACT' });
    let gets = 0;
    let posts = 0;
    let readState: 'pending' | 'failed' | 'completed' = 'pending';
    const execution = mockExecution({
      knowledgeBaseId: 1,
      conversationId: conversation.id,
      agentMode: 'REACT',
      question: '问题',
    });
    server.use(
      http.post('*/api/v1/agent/executions', () => {
        posts += 1;
        return success(execution);
      }),
      http.get('*/api/v1/agent/executions/:id', () => {
        gets += 1;
        return readState === 'failed'
          ? HttpResponse.json(
              {
                code: 'INTERNAL_ERROR',
                message: '状态读取失败',
                data: null,
                traceId: 'execution-read-error',
              },
              { status: 500 },
            )
          : success(readState === 'completed' ? completeMockExecution(execution) : execution);
      }),
    );
    renderChat('/knowledge-bases/1/chat/' + conversation.id);
    const input = await screen.findByLabelText('消息');
    await waitFor(() => expect(input).toBeEnabled());
    fireEvent.change(input, { target: { value: '问题' } });
    fireEvent.keyDown(input, { key: 'Enter' });
    const reply = await screen.findByLabelText('Agent 执行回复');
    const assistantId = reply.getAttribute('data-assistant-message-id');
    const readsBeforeRefresh = gets;
    readState = 'failed';
    if (!screen.getByText('任务信息').closest('details')?.open)
      fireEvent.click(screen.getByText('任务信息'));
    fireEvent.click(screen.getByRole('button', { name: '刷新执行状态' }));
    expect(await screen.findByText('状态读取失败')).toBeInTheDocument();
    expect(input).toBeDisabled();
    expect(reply).toHaveAttribute('data-assistant-message-id', assistantId);
    readState = 'completed';
    if (!screen.getByText('任务信息').closest('details')?.open)
      fireEvent.click(screen.getByText('任务信息'));
    fireEvent.click(screen.getByRole('button', { name: '刷新执行状态' }));
    expect(await screen.findByText('Mock 执行回答')).toBeInTheDocument();
    await waitFor(() => expect(screen.queryByText('状态读取失败')).not.toBeInTheDocument());
    expect(posts).toBe(1);
    expect(gets).toBeGreaterThanOrEqual(readsBeforeRefresh + 2);
  });

  it.each(['PAUSED', 'FAILED', 'CANCELLED', 'SUCCEEDED'] as const)(
    'honors an initial %s execution response instead of assuming it is PENDING',
    async (status) => {
      const conversation = addConversation(1, { agentMode: 'PLAN_EXECUTE_REFLECT' });
      const execution = {
        ...mockExecution({
          knowledgeBaseId: 1,
          conversationId: conversation.id,
          agentMode: 'PLAN_EXECUTE_REFLECT',
          question: '问题',
        }),
        status,
      };
      server.use(http.post('*/api/v1/agent/executions', () => success(execution)));
      renderChat('/knowledge-bases/1/chat/' + conversation.id);
      const input = await screen.findByLabelText('消息');
      await waitFor(() => expect(input).toBeEnabled());
      fireEvent.change(input, { target: { value: '问题' } });
      fireEvent.keyDown(input, { key: 'Enter' });
      await screen.findByLabelText('Agent 执行回复');
      const message = {
        PAUSED: '任务已暂停，等待恢复。',
        FAILED: '任务执行失败，请核对问题后再发送。',
        CANCELLED: '任务已取消。',
        SUCCEEDED: '执行已完成，但服务未返回可展示的回答。',
      }[status];
      expect(screen.getByText(message)).toBeInTheDocument();
      await waitFor(() =>
        status === 'PAUSED' ? expect(input).toBeDisabled() : expect(input).toBeEnabled(),
      );
    },
  );

  it('clears execution associations and cached details when the finished conversation is deleted', async () => {
    const conversation = addConversation(1, { title: '待删除任务会话', agentMode: 'REACT' });
    const execution = completeMockExecution(
      mockExecution({
        knowledgeBaseId: 1,
        conversationId: conversation.id,
        agentMode: 'REACT',
        question: '问题',
      }),
    );
    server.use(http.post('*/api/v1/agent/executions', () => success(execution)));
    const { queryClient } = renderChat('/knowledge-bases/1/chat/' + conversation.id);
    const input = await screen.findByLabelText('消息');
    await waitFor(() => expect(input).toBeEnabled());
    fireEvent.change(input, { target: { value: '问题' } });
    fireEvent.keyDown(input, { key: 'Enter' });
    expect(await screen.findByText('Mock 执行回答')).toBeInTheDocument();
    const remove = screen.getByRole('button', { name: '删除会话 待删除任务会话' });
    await waitFor(() => expect(remove).toBeEnabled());
    fireEvent.click(remove);
    const dialog = await screen.findByRole('dialog', { name: '删除会话' });
    fireEvent.click(within(dialog).getByRole('button', { name: '删 除' }));
    await waitFor(() =>
      expect(screen.getByLabelText('当前地址')).toHaveTextContent(/^\/knowledge-bases\/1\/chat$/),
    );
    expect(
      queryClient.getQueryData(queryKeys.executions.detail(1, execution.executionId)),
    ).toBeUndefined();
    expect(
      queryClient
        .getMutationCache()
        .findAll({ mutationKey: queryKeys.messages.send(conversation.id) }),
    ).toHaveLength(0);
  });

  it('appends a new pending turn after completed executions and gives each turn a distinct association', async () => {
    const conversation = addConversation(1, { agentMode: 'REACT' });
    const first = completeMockExecution(
      mockExecution({
        knowledgeBaseId: 1,
        conversationId: conversation.id,
        agentMode: 'REACT',
        question: '第一个执行问题',
      }),
    );
    const second = mockExecution({
      knowledgeBaseId: 1,
      conversationId: conversation.id,
      agentMode: 'REACT',
      question: '第二个执行问题',
    });
    let release!: () => void;
    const gate = new Promise<void>((resolve) => {
      release = resolve;
    });
    const keys: (string | null)[] = [];
    server.use(
      http.post('*/api/v1/agent/executions', async ({ request }) => {
        keys.push(request.headers.get('Idempotency-Key'));
        if (keys.length === 1) return success(first);
        await gate;
        return success(second);
      }),
    );
    renderChat('/knowledge-bases/1/chat/' + conversation.id);
    const input = await screen.findByLabelText('消息');
    await waitFor(() => expect(input).toBeEnabled());
    fireEvent.change(input, { target: { value: '第一个执行问题' } });
    fireEvent.keyDown(input, { key: 'Enter' });
    await screen.findByText('Mock 执行回答');
    await waitFor(() => expect(input).toBeEnabled());
    fireEvent.change(input, { target: { value: '第二个执行问题' } });
    fireEvent.keyDown(input, { key: 'Enter' });
    let pendingId: string | null = null;
    try {
      const pending = await screen.findByLabelText('待回复的 AI 消息');
      pendingId = pending.getAttribute('data-assistant-message-id');
      const transcript = screen.getByLabelText('消息历史').textContent!;
      expect(transcript.indexOf('第二个执行问题')).toBeGreaterThan(
        transcript.indexOf('Mock 执行回答'),
      );
      await waitFor(() => expect(keys).toHaveLength(2));
      expect(keys[0]).not.toBe(keys[1]);
    } finally {
      await act(async () => release());
    }
    await waitFor(() => expect(screen.getAllByLabelText('Agent 执行回复')).toHaveLength(2));
    const replies = screen.getAllByLabelText('Agent 执行回复');
    expect(replies[0]).toHaveAttribute('data-execution-id', first.executionId);
    expect(replies[1]).toHaveAttribute('data-execution-id', second.executionId);
    expect(replies[1]).toHaveAttribute('data-assistant-message-id', pendingId);
  });

  it('restores a bookmarked conversation with a fresh query cache and opens the returned citation', async () => {
    renderChat();
    expect(await screen.findByText('如何避免重复处理？')).toBeInTheDocument();
    expect(screen.getByText('Mock 历史回答')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: '打开会话 重试机制分析' })).toHaveAttribute(
      'aria-current',
      'page',
    );
    fireEvent.click(screen.getByRole('button', { name: '[C1] catalyst-paper.pdf' }));
    const drawer = await screen.findByRole('dialog', { name: '[C1] 引用详情' });
    expect(within(drawer).getByText('第 5 页')).toBeInTheDocument();
    fireEvent.click(within(drawer).getByRole('button', { name: '技术信息' }));
    expect(within(drawer).getByText('1001')).toBeInTheDocument();
    expect(
      within(drawer).getByText('消费者会先检查幂等键；已处理的消息不会再次执行业务逻辑。'),
    ).toBeInTheDocument();
    fireEvent.click(within(drawer).getByRole('button', { name: /查看文档/ }));
    expect(await screen.findByText('引用文档页')).toBeInTheDocument();
  });

  it('creates a ReAct conversation, navigates to its server ID and displays empty history', async () => {
    renderChat('/knowledge-bases/2/chat');
    expect(await screen.findByText('发送第一个问题后，会话会保存在这里。')).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: '高级会话' }));
    const dialog = await screen.findByRole('dialog', { name: '创建会话' });
    fireEvent.change(within(dialog).getByLabelText('会话标题'), { target: { value: '多轮调研' } });
    fireEvent.mouseDown(within(dialog).getByRole('combobox'));
    fireEvent.click(
      await screen.findByText('ReAct', { selector: '.ant-select-item-option-content' }),
    );
    fireEvent.click(within(dialog).getByRole('button', { name: '创 建' }));
    expect(await screen.findByText('还没有消息，发送第一个问题开始对话。')).toBeInTheDocument();
    expect(screen.getByLabelText('当前地址')).toHaveTextContent('/knowledge-bases/2/chat/303');
    expect(getConversation(303)).toMatchObject({
      knowledgeBaseId: 2,
      title: '多轮调研',
      agentMode: 'REACT',
    });
    expect(screen.getByLabelText('消息')).toBeEnabled();
  });

  it('renders an optimistic user turn, prevents duplicate sends, then replaces it with persisted messages', async () => {
    let release!: () => void;
    const responseGate = new Promise<void>((resolve) => {
      release = resolve;
    });
    let requests = 0;
    server.use(
      http.post('*/api/v1/conversations/301/messages', async ({ request }) => {
        requests += 1;
        const body = (await request.json()) as { content: string };
        await responseGate;
        return success(addConversationTurn(301, body.content));
      }),
    );
    renderChat();
    const input = await screen.findByLabelText('消息');
    await waitFor(() => expect(input).toBeEnabled());
    expect(screen.getByRole('button', { name: /发送$/ })).toBeDisabled();
    fireEvent.change(input, { target: { value: '   ' } });
    expect(screen.getByRole('button', { name: /发送$/ })).toBeDisabled();
    fireEvent.change(input, { target: { value: '请进一步解释幂等性' } });
    fireEvent.keyDown(input, { key: 'Enter', shiftKey: true });
    expect(requests).toBe(0);
    fireEvent.keyDown(input, { key: 'Enter', isComposing: true });
    expect(requests).toBe(0);
    fireEvent.keyDown(input, { key: 'Enter' });
    fireEvent.keyDown(input, { key: 'Enter' });
    try {
      expect(await screen.findByLabelText('正在发送的消息')).toHaveTextContent(
        '请进一步解释幂等性',
      );
      await waitFor(() => expect(requests).toBe(1));
      expect(input).toBeDisabled();
      expect(screen.getByText('正在等待 AI 回答…')).toBeInTheDocument();
      expect(screen.getByRole('button', { name: '删除会话 重试机制分析' })).toBeDisabled();
    } finally {
      await act(async () => release());
    }
    expect(await screen.findByText('Mock 回答')).toBeInTheDocument();
    await waitFor(() => expect(screen.queryByLabelText('正在发送的消息')).not.toBeInTheDocument());
    expect(screen.getAllByText('请进一步解释幂等性')).toHaveLength(1);
    expect(getConversationMessages(301)).toHaveLength(4);
  });

  it('rolls back a failed optimistic turn, restores the draft and keeps server history', async () => {
    server.use(
      http.post('*/api/v1/conversations/301/messages', () =>
        HttpResponse.json(
          {
            code: 'CONVERSATION_ANSWERING_UNAVAILABLE',
            message: '模型服务暂不可用',
            data: null,
            traceId: 'failed-turn',
          },
          { status: 503 },
        ),
      ),
    );
    renderChat();
    const input = await screen.findByLabelText('消息');
    await waitFor(() => expect(input).toBeEnabled());
    fireEvent.change(input, { target: { value: '保留这条草稿' } });
    fireEvent.click(screen.getByRole('button', { name: /发送$/ }));
    expect(await screen.findByText('模型服务暂不可用')).toBeInTheDocument();
    await waitFor(() => expect(input).toHaveValue('保留这条草稿'));
    expect(screen.queryByLabelText('正在发送的消息')).not.toBeInTheDocument();
    expect(screen.getByText('如何避免重复处理？')).toBeInTheDocument();
    expect(getConversationMessages(301)).toHaveLength(2);
  });

  it('keeps pending turns isolated when changing conversations and prevents a second POST on returning', async () => {
    let release!: () => void;
    const responseGate = new Promise<void>((resolve) => {
      release = resolve;
    });
    server.use(
      http.post('*/api/v1/conversations/301/messages', async ({ request }) => {
        const body = (await request.json()) as { content: string };
        await responseGate;
        return success(addConversationTurn(301, body.content));
      }),
    );
    renderChat();
    const input = await screen.findByLabelText('消息');
    await waitFor(() => expect(input).toBeEnabled());
    fireEvent.change(input, { target: { value: '仅属于会话一' } });
    fireEvent.keyDown(input, { key: 'Enter' });
    try {
      await screen.findByLabelText('正在发送的消息');
      fireEvent.click(screen.getByRole('button', { name: '打开会话 旧版 RAG 会话' }));
      expect(await screen.findByText('还没有消息，发送第一个问题开始对话。')).toBeInTheDocument();
      expect(screen.queryByText('仅属于会话一')).not.toBeInTheDocument();
      fireEvent.click(screen.getByRole('button', { name: '打开会话 重试机制分析' }));
      expect(await screen.findByLabelText('正在发送的消息')).toHaveTextContent('仅属于会话一');
      expect(screen.getByLabelText('消息')).toBeDisabled();
    } finally {
      await act(async () => release());
    }
    await waitFor(() => expect(screen.queryByLabelText('正在发送的消息')).not.toBeInTheDocument());
    expect(await screen.findByText('仅属于会话一')).toBeInTheDocument();
  });

  it('resolves a bookmarked conversation beyond the first list page before loading its messages', async () => {
    const original = getConversation(301)!;
    const firstPage: Conversation[] = Array.from({ length: 20 }, (_, index) => ({
      ...original,
      id: 400 + index,
      title: '其他会话 ' + index,
    }));
    const pages: number[] = [];
    server.use(
      http.get('*/api/v1/knowledge-bases/1/conversations', ({ request }) => {
        const page = Number(new URL(request.url).searchParams.get('page'));
        pages.push(page);
        return success({
          content: page === 0 ? firstPage : [original],
          page,
          size: 20,
          totalPages: 2,
          totalElements: 21,
        });
      }),
    );
    renderChat();
    expect(await screen.findByText('如何避免重复处理？')).toBeInTheDocument();
    expect(pages).toEqual([0, 1]);
  });

  it('rejects cross-knowledge-base bookmarks without requesting their message history', async () => {
    let historyReads = 0;
    server.use(
      http.get('*/api/v1/conversations/301/messages', () => {
        historyReads += 1;
        return success({ content: [], page: 0, size: 100, totalPages: 0, totalElements: 0 });
      }),
    );
    renderChat('/knowledge-bases/2/chat/301');
    expect(await screen.findByText('会话不存在或不属于当前知识库')).toBeInTheDocument();
    expect(historyReads).toBe(0);
  });

  it('shows list and history failures and allows explicit retries', async () => {
    let listReads = 0;
    server.use(
      http.get('*/api/v1/knowledge-bases/1/conversations', () => {
        listReads += 1;
        return listReads === 1
          ? HttpResponse.json(
              { code: 'INTERNAL_ERROR', message: '列表服务暂不可用', data: null },
              { status: 500 },
            )
          : success({
              content: [getConversation(301)],
              page: 0,
              size: 20,
              totalPages: 1,
              totalElements: 1,
            });
      }),
    );
    let historyReads = 0;
    server.use(
      http.get('*/api/v1/conversations/301/messages', () => {
        historyReads += 1;
        return historyReads === 1
          ? HttpResponse.json(
              { code: 'INTERNAL_ERROR', message: '消息读取失败', data: null },
              { status: 500 },
            )
          : success({
              content: getConversationMessages(301),
              page: 0,
              size: 100,
              totalPages: 1,
              totalElements: 2,
            });
      }),
    );
    renderChat();
    expect((await screen.findAllByText('列表服务暂不可用')).length).toBeGreaterThan(0);
    fireEvent.click(screen.getByRole('button', { name: '重试会话列表' }));
    expect(await screen.findByText('消息读取失败')).toBeInTheDocument();
    expect(screen.getByLabelText('消息')).toBeDisabled();
    fireEvent.click(screen.getByRole('button', { name: /重试$/ }));
    expect(await screen.findByText('如何避免重复处理？')).toBeInTheDocument();
    expect(screen.getByLabelText('消息')).toBeEnabled();
  });

  it('confirms deletion, invalidates the scoped list and clears the deleted message cache', async () => {
    const { queryClient } = renderChat();
    await screen.findByText('如何避免重复处理？');
    fireEvent.click(screen.getByRole('button', { name: '删除会话 重试机制分析' }));
    const dialog = await screen.findByRole('dialog', { name: '删除会话' });
    fireEvent.click(within(dialog).getByRole('button', { name: '删 除' }));
    await waitFor(() =>
      expect(screen.getByLabelText('当前地址')).toHaveTextContent(/^\/knowledge-bases\/1\/chat$/),
    );
    await waitFor(() =>
      expect(
        screen.queryByRole('button', { name: '打开会话 重试机制分析' }),
      ).not.toBeInTheDocument(),
    );
    expect(queryClient.getQueryData(queryKeys.messages.list(301))).toBeUndefined();
    expect(getConversation(301)).toBeUndefined();
  });

  it('keeps the conversation and shows backend errors when create or delete fails', async () => {
    server.use(
      http.post('*/api/v1/knowledge-bases/1/conversations', () =>
        HttpResponse.json(
          { code: 'INTERNAL_ERROR', message: '创建失败', data: null },
          { status: 500 },
        ),
      ),
      http.delete('*/api/v1/conversations/301', () =>
        HttpResponse.json(
          { code: 'INTERNAL_ERROR', message: '删除失败', data: null },
          { status: 500 },
        ),
      ),
    );
    renderChat();
    await screen.findByText('如何避免重复处理？');
    fireEvent.click(screen.getByRole('button', { name: '高级会话' }));
    const createDialog = await screen.findByRole('dialog', { name: '创建会话' });
    fireEvent.click(within(createDialog).getByRole('button', { name: '创 建' }));
    expect(await screen.findByText('创建失败')).toBeInTheDocument();
    fireEvent.click(within(createDialog).getByRole('button', { name: '取 消' }));
    await waitFor(() => expect(createDialog).not.toBeInTheDocument());
    const deleteButton = screen.getByRole('button', { name: '删除会话 重试机制分析' });
    expect(deleteButton).toBeEnabled();
    fireEvent.click(deleteButton);
    const deleteDialog = await screen.findByRole('dialog', { name: '删除会话' });
    fireEvent.click(within(deleteDialog).getByRole('button', { name: '删 除' }));
    expect(await screen.findByText('删除失败')).toBeInTheDocument();
    expect(getConversation(301)).toBeDefined();
    expect(screen.getByText('如何避免重复处理？')).toBeInTheDocument();
  });

  it('treats user content, AI Markdown and citation filenames as untrusted text', async () => {
    const original = getConversationMessages(301);
    server.use(
      http.get('*/api/v1/conversations/301/messages', () =>
        success({
          content: [
            { ...original[0], content: '<img src=x onerror=alert(1)>' },
            {
              ...original[1],
              content: '<script>alert(1)</script>\n\n[危险链接](javascript:alert(1)) [C1]',
              citations: [
                {
                  ...original[1].citations[0],
                  documentName: '<img onerror=alert(1)>',
                  quote: '<script>quote</script>',
                },
              ],
            },
          ],
          page: 0,
          size: 100,
          totalPages: 1,
          totalElements: 2,
        }),
      ),
    );
    const { container } = renderChat();
    expect(await screen.findByText('<img src=x onerror=alert(1)>')).toBeInTheDocument();
    expect(container.querySelector('script, img')).toBeNull();
    expect(container.querySelector('a[href^="javascript:"]')).toBeNull();
    fireEvent.click(screen.getByRole('button', { name: '[C1] <img onerror=alert(1)>' }));
    const drawer = await screen.findByRole('dialog', { name: '[C1] 引用详情' });
    expect(within(drawer).getByText('<script>quote</script>')).toBeInTheDocument();
    expect(drawer.querySelector('script, img')).toBeNull();
  });
  it('creates only on the first send and sends once under StrictMode and repeated Enter', async () => {
    let creates = 0;
    let sends = 0;
    let release!: () => void;
    const gate = new Promise<void>((resolve) => {
      release = resolve;
    });
    server.use(
      http.post('*/api/v1/knowledge-bases/2/conversations', async ({ request }) => {
        creates += 1;
        expect(await request.json()).toEqual({
          title: '文档如何处理重复消息？',
          agentMode: 'RETRIEVAL_FIRST',
        });
        await gate;
        return success(
          addConversation(2, { title: '文档如何处理重复消息？', agentMode: 'RETRIEVAL_FIRST' }),
        );
      }),
      http.post('*/api/v1/conversations/303/messages', async ({ request }) => {
        sends += 1;
        expect(await request.json()).toEqual({ content: '文档如何处理重复消息？' });
        return success(addConversationTurn(303, '文档如何处理重复消息？'));
      }),
    );
    const view = renderChat('/knowledge-bases/2/chat', true);
    const input = await screen.findByLabelText('消息');
    expect(creates).toBe(0);
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
    fireEvent.change(input, { target: { value: '文档如何处理重复消息？' } });
    fireEvent.keyDown(input, { key: 'Enter', isComposing: true });
    fireEvent.keyDown(input, { key: 'Enter', shiftKey: true });
    expect(creates).toBe(0);
    fireEvent.keyDown(input, { key: 'Enter' });
    fireEvent.keyDown(input, { key: 'Enter' });
    try {
      await waitFor(() => expect(creates).toBe(1));
      expect(sends).toBe(0);
    } finally {
      await act(async () => release());
    }
    expect(await screen.findByText('Mock 回答')).toBeInTheDocument();
    expect(screen.getByLabelText('当前地址')).toHaveTextContent('/knowledge-bases/2/chat/303');
    expect(creates).toBe(1);
    expect(sends).toBe(1);
    expect(getConversationMessages(303)).toHaveLength(2);
    view.unmount();
    renderChat('/knowledge-bases/2/chat/303', true);
    expect(await screen.findByText('Mock 回答')).toBeInTheDocument();
    expect(creates).toBe(1);
    expect(sends).toBe(1);
  });

  it('keeps the first question after creation fails and only retries on demand', async () => {
    let creates = 0;
    server.use(
      http.post('*/api/v1/knowledge-bases/2/conversations', () => {
        creates += 1;
        return HttpResponse.json(
          { code: 'UNAVAILABLE', message: '会话暂时无法创建', data: null },
          { status: 503 },
        );
      }),
    );
    renderChat('/knowledge-bases/2/chat');
    const input = await screen.findByLabelText('消息');
    fireEvent.change(input, { target: { value: '保留这个问题' } });
    fireEvent.click(screen.getByRole('button', { name: '发送' }));
    expect(await screen.findByText('会话暂时无法创建')).toBeInTheDocument();
    expect(screen.getByLabelText('消息')).toHaveValue('保留这个问题');
    expect(screen.getByLabelText('当前地址')).toHaveTextContent('/knowledge-bases/2/chat');
    expect(creates).toBe(1);
    fireEvent.click(screen.getByRole('button', { name: '发送' }));
    await waitFor(() => expect(creates).toBe(2));
  });

  it('retries the first failed answer in the created conversation without creating another one', async () => {
    let creates = 0;
    let sends = 0;
    server.use(
      http.post('*/api/v1/knowledge-bases/2/conversations', () => {
        creates += 1;
        return success(addConversation(2, { title: '首次回答失败', agentMode: 'RETRIEVAL_FIRST' }));
      }),
      http.post('*/api/v1/conversations/303/messages', () => {
        sends += 1;
        return sends === 1
          ? HttpResponse.json(
              { code: 'CHAT_MODEL_UNAVAILABLE', message: '模型暂时不可用', data: null },
              { status: 503 },
            )
          : success(addConversationTurn(303, '首次回答失败'));
      }),
    );
    renderChat('/knowledge-bases/2/chat', true);
    fireEvent.change(await screen.findByLabelText('消息'), { target: { value: '首次回答失败' } });
    fireEvent.click(screen.getByRole('button', { name: '发送' }));
    expect(await screen.findByText('模型暂时不可用')).toBeInTheDocument();
    await waitFor(() => expect(screen.getByLabelText('消息')).toHaveValue('首次回答失败'));
    await waitFor(() => expect(screen.getByRole('button', { name: '发送' })).toBeEnabled());
    expect(sends).toBe(1);
    fireEvent.click(screen.getByRole('button', { name: '发送' }));
    expect(await screen.findByText('Mock 回答')).toBeInTheDocument();
    expect(sends).toBe(2);
    expect(creates).toBe(1);
  });
});
