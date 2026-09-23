import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { fireEvent, render, screen } from '@testing-library/react';
import { HttpResponse, http } from 'msw';
import { MemoryRouter } from 'react-router-dom';
import { describe, expect, it } from 'vitest';
import { server } from '../../test/mocks/server';
import { QuickQueryPanel } from './QuickQueryPanel';

function renderQuickQueryPanel() {
  const queryClient = new QueryClient({
    defaultOptions: {
      queries: { retry: false },
      mutations: { retry: false },
    },
  });

  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter>
        <QuickQueryPanel knowledgeBaseId={1} />
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

describe('QuickQueryPanel', () => {
  it('renders the returned answer, degraded warning, and real citation drawer metadata', async () => {
    let requestBody: unknown;
    server.use(
      http.post('*/api/v1/knowledge-bases/1/query', async ({ request }) => {
        requestBody = await request.json();
        return successfulQueryResponse();
      }),
    );

    renderQuickQueryPanel();

    expect(screen.getByText(/回答会附带服务端返回的可验证引用/)).toBeInTheDocument();

    fireEvent.change(screen.getByLabelText('问题'), { target: { value: '系统如何处理重复消息？' } });
    fireEvent.click(screen.getByRole('button', { name: /开始问答$/ }));

    expect(
      await screen.findByText('系统会使用幂等键识别重复消息，并在确认后安全跳过重复处理。'),
    ).toBeInTheDocument();
    expect(requestBody).toEqual({
      question: '系统如何处理重复消息？',
      topK: 8,
      rerank: true,
      stream: false,
    });
    expect(screen.getByText('检索结果已降级')).toBeInTheDocument();

    fireEvent.click(screen.getByRole('button', { name: '[C1] catalyst-paper.pdf' }));

    expect(await screen.findByText('[C1] 引用详情')).toBeInTheDocument();
    expect(screen.getByText('第 5 页')).toBeInTheDocument();
    expect(screen.getByText('消费者会先检查幂等键；已处理的消息不会再次执行业务逻辑。')).toBeInTheDocument();
    expect(screen.getByText('101')).toBeInTheDocument();
    expect(screen.getByText('1001')).toBeInTheDocument();
  });

  it('shows a user-safe request error instead of a fabricated answer', async () => {
    server.use(
      http.post('*/api/v1/knowledge-bases/1/query', () =>
        HttpResponse.json(
          {
            code: 'CHAT_MODEL_UNAVAILABLE',
            message: '模型服务暂时不可用，请稍后重试。',
            data: null,
            traceId: 'rag-error-trace',
          },
          { status: 503 },
        ),
      ),
    );

    renderQuickQueryPanel();

    fireEvent.change(screen.getByLabelText('问题'), { target: { value: '系统如何处理重复消息？' } });
    fireEvent.click(screen.getByRole('button', { name: /开始问答$/ }));

    expect(await screen.findByText('模型服务暂时不可用，请稍后重试。')).toBeInTheDocument();
    expect(screen.queryByText('回答')).not.toBeInTheDocument();
  });
});

function successfulQueryResponse() {
  return HttpResponse.json({
    code: 'SUCCESS',
    message: 'OK',
    data: {
      answer: '系统会使用幂等键识别重复消息，并在确认后安全跳过重复处理。[C1]',
      citations: [
        {
          citationId: 'C1',
          documentId: 101,
          documentName: 'catalyst-paper.pdf',
          chunkId: 1001,
          pageFrom: 5,
          pageTo: 5,
          quote: '消费者会先检查幂等键；已处理的消息不会再次执行业务逻辑。',
        },
      ],
      retrieval: {
        degraded: true,
        candidateCount: 1,
      },
    },
    traceId: 'quick-query-trace',
  });
}
