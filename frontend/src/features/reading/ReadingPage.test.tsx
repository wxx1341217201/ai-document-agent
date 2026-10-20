import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { ConfigProvider } from 'antd';
import { HttpResponse, http, delay } from 'msw';
import { createMemoryRouter, RouterProvider } from 'react-router-dom';
import { describe, expect, it } from 'vitest';
import { server } from '../../test/mocks/server';
import {
  mockReadingTask,
  mockReadingResult,
  readingNotes,
  readingRequests,
  readingResults,
  readingSource,
  readingTaskId,
  readingTasks,
} from '../../test/mocks/reading-fixtures';
import { ReadingPage } from './ReadingPage';

const path = `/knowledge-bases/1/reading/${readingTaskId}`;
const success = (data: unknown) => HttpResponse.json({ code: 'SUCCESS', message: 'OK', data });
function mount(url = path) {
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false, gcTime: 0 }, mutations: { retry: false } },
  });
  const router = createMemoryRouter(
    [
      { path: '/reading', element: <ReadingPage /> },
      { path: '/knowledge-bases/:kbId/reading', element: <ReadingPage /> },
      { path: '/knowledge-bases/:kbId/reading/:taskId', element: <ReadingPage /> },
    ],
    { initialEntries: [url] },
  );
  const rendered = render(
    <QueryClientProvider client={client}>
      <ConfigProvider theme={{ token: { motion: false } }}>
        <RouterProvider router={router} />
      </ConfigProvider>
    </QueryClientProvider>,
  );
  return { ...rendered, client };
}

describe('协同阅读与独立笔记（软件流程夹具）', { timeout: 60_000 }, () => {
  it('submits the explicitly selected documents with one stable request key', async () => {
    mount('/knowledge-bases/1/reading');
    fireEvent.click(await screen.findByRole('button', { name: /新建阅读任务/ }));
    fireEvent.click(await screen.findByRole('checkbox', { name: '选择资料 catalyst-paper.pdf' }));
    fireEvent.change(screen.getByRole('textbox', { name: '阅读目标' }), {
      target: { value: '整理本次选定资料' },
    });
    fireEvent.click(screen.getByRole('button', { name: '开始阅读' }));
    await waitFor(() => expect(readingRequests.size).toBe(1));
    const submitted = JSON.parse([...readingRequests.values()][0].signature);
    expect(submitted.documentIds).toEqual([101]);
    expect(submitted.knowledgeBaseId).toBe(1);
    expect([...readingRequests.keys()][0]).toMatch(/^[a-f0-9-]{36}$/);
    await screen.findByRole('heading', { name: '主题阅读' });
  });

  it('keeps unsaved edits through a new analysis round, then persists them across reopening', async () => {
    const mounted = mount();
    const editor = await screen.findByRole('textbox', { name: '笔记内容' });
    fireEvent.change(editor, { target: { value: '保留我的独立思考。[C1]' } });
    fireEvent.click(screen.getByRole('button', { name: '继续补全' }));
    await screen.findByText('排队中');
    expect(editor).toHaveValue('保留我的独立思考。[C1]');
    fireEvent.click(screen.getByRole('button', { name: '保存修改' }));
    await screen.findByText('已保存 · 修订 3');
    expect(readingNotes.get(readingTaskId)?.content).toBe('保留我的独立思考。[C1]');
    mounted.unmount();
    mount();
    expect(await screen.findByRole('textbox', { name: '笔记内容' })).toHaveValue(
      '保留我的独立思考。[C1]',
    );
  });

  it('preserves the local draft on 409 and saves only after explicit comparison with the latest version', async () => {
    let posts = 0;
    server.use(
      http.put('*/api/v1/reading/tasks/:id/note', async ({ request }) => {
        posts++;
        const body = (await request.json()) as { content: string; expectedVersion: number };
        const note = readingNotes.get(readingTaskId)!;
        if (posts === 1) {
          note.version = 3;
          note.content = '另一个窗口保存的笔记';
          return HttpResponse.json(
            { code: 'READING_CONFLICT', message: '笔记已变化', data: null },
            { status: 409 },
          );
        }
        expect(body.expectedVersion).toBe(3);
        note.content = body.content;
        note.version = 4;
        return success(note);
      }),
    );
    mount();
    const editor = await screen.findByRole('textbox', { name: '笔记内容' });
    fireEvent.change(editor, { target: { value: '本地修改不会丢失' } });
    fireEvent.click(screen.getByRole('button', { name: '保存修改' }));
    const compare = await screen.findByRole('button', { name: '对照并合并' });
    expect(posts).toBe(1);
    expect(editor).toHaveValue('本地修改不会丢失');
    fireEvent.click(compare);
    await screen.findByText('另一个窗口保存的笔记');
    fireEvent.click(screen.getByRole('button', { name: '保留本地内容并基于最新版本编辑' }));
    fireEvent.click(screen.getByRole('button', { name: '保存修改' }));
    await screen.findByText('已保存 · 修订 4');
    expect(posts).toBe(2);
    expect(editor).toHaveValue('本地修改不会丢失');
  });

  it('opens saved original text without fetching the changed live document', async () => {
    let liveReads = 0;
    server.use(
      http.get('*/api/v1/knowledge-bases/1/documents/101', () => {
        liveReads++;
        return new HttpResponse(null, { status: 404 });
      }),
    );
    mount();
    const citation = await screen.findByRole('button', { name: '[C1]' });
    fireEvent.click(citation);
    await screen.findByText(readingSource.text);
    expect(screen.getByRole('link', { name: '打开当前版本的文档' })).toHaveAttribute(
      'href',
      '/knowledge-bases/1/documents/101',
    );
    expect(liveReads).toBe(0);
  });

  it('waits for manual recovery and sends the same round and version once', async () => {
    let task = {
      ...mockReadingTask(),
      status: 'INTERRUPTED' as 'INTERRUPTED' | 'PENDING',
      usage: { ...mockReadingTask().usage, modelCalls: 6 },
    };
    let posts = 0;
    server.use(
      http.get('*/api/v1/reading/tasks/:id', () => success(task)),
      http.post('*/api/v1/reading/tasks/:id/resume', async ({ request }) => {
        posts++;
        expect(await request.json()).toEqual({ expectedVersion: 4, expectedRoundNo: 1 });
        task = { ...task, status: 'PENDING', version: 5 };
        return success(task);
      }),
    );
    mount();
    const resume = await screen.findByRole('button', { name: '恢复本轮' });
    expect(posts).toBe(0);
    fireEvent.click(resume);
    fireEvent.click(resume);
    await screen.findByText('排队中');
    expect(posts).toBe(1);
    expect(screen.getByText('模型请求 6 / 8')).toBeInTheDocument();
  });

  it('creates a linked follow-up without changing the original task or range', async () => {
    mount();
    fireEvent.click(await screen.findByRole('button', { name: '提出新问题' }));
    fireEvent.change(screen.getByRole('textbox', { name: '追问内容' }), {
      target: { value: '这些观察还有什么局限？' },
    });
    fireEvent.click(screen.getByRole('button', { name: '创建关联阅读任务' }));
    await waitFor(() => expect(readingTasks.size).toBe(2));
    const child = [...readingTasks.values()].find((t) => t.id !== readingTaskId)!;
    expect(child.parentTaskId).toBe(readingTaskId);
    expect(child.sessionId).toBe(readingTasks.get(readingTaskId)?.sessionId);
    expect(child.documents.map((d) => d.id)).toEqual([101]);
    expect(readingTasks.get(readingTaskId)?.roundNo).toBe(1);
  });

  it('displays NONE as no reliable result rather than full completion', async () => {
    const task = { ...mockReadingTask(), completion: 'NONE' as const, latestResultId: null };
    server.use(http.get('*/api/v1/reading/tasks/:id', () => success(task)));
    mount();
    await screen.findByText('尚无可靠结果');
    expect(screen.queryByText('全部完成')).not.toBeInTheDocument();
    expect(screen.getByRole('button', { name: '继续补全' })).toBeEnabled();
  });

  it('keeps the editing buffer mounted when a new immutable result arrives', async () => {
    const { client } = mount();
    const editor = await screen.findByRole('textbox', { name: '笔记内容' });
    fireEvent.change(editor, { target: { value: 'AI 更新时我还在编辑 [C1]' } });
    const current = readingTasks.get(readingTaskId)!;
    current.latestResultId = '12121212-1212-4212-8212-121212121212';
    current.version++;
    const next = mockReadingResult(current);
    next.id = current.latestResultId;
    next.markdown = '# 新一版结果\n\n仍然只是待采纳的草稿。';
    readingResults.set(next.id, next);
    await client.invalidateQueries({ queryKey: ['reading-task', 1, readingTaskId] });
    await screen.findByText('仍然只是待采纳的草稿。');
    expect(screen.getByRole('textbox', { name: '笔记内容' })).toBe(editor);
    expect(editor).toHaveValue('AI 更新时我还在编辑 [C1]');
    expect(readingNotes.get(readingTaskId)?.content).not.toBe('AI 更新时我还在编辑 [C1]');
  });

  it('adopts selected AI paragraphs only after an explicit save', async () => {
    mount();
    const editor = await screen.findByRole('textbox', { name: '笔记内容' });
    const before = readingNotes.get(readingTaskId)!.content;
    fireEvent.click(screen.getByRole('tab', { name: 'AI 新版草稿 · 待采纳' }));
    fireEvent.click(screen.getByRole('button', { name: '对照当前笔记' }));
    expect(
      await screen.findByRole('dialog', { name: '当前笔记与 AI 草稿对照' }),
    ).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: 'Close' }));
    fireEvent.click(screen.getByRole('checkbox', { name: '选择草稿第 3 段' }));
    fireEvent.click(screen.getByRole('button', { name: '将所选段落加入笔记' }));
    expect(readingNotes.get(readingTaskId)?.content).toBe(before);
    fireEvent.click(screen.getByRole('tab', { name: '我的笔记' }));
    expect((editor as HTMLTextAreaElement).value).toContain(before);
    fireEvent.click(screen.getByRole('button', { name: '保存修改' }));
    await screen.findByText('已保存 · 修订 3');
    expect(readingNotes.get(readingTaskId)?.content).toContain('观察组的响应高于对照组');
  });

  it('preserves typing performed while an earlier save is in flight', async () => {
    server.use(
      http.put('*/api/v1/reading/tasks/:id/note', async ({ request }) => {
        const submitted = (await request.json()) as { content: string };
        await delay(1500);
        const note = readingNotes.get(readingTaskId)!;
        note.content = submitted.content;
        note.version++;
        return success(note);
      }),
    );
    mount();
    const editor = await screen.findByRole('textbox', { name: '笔记内容' });
    fireEvent.change(editor, { target: { value: '第一次保存的内容' } });
    fireEvent.click(screen.getByRole('button', { name: '保存修改' }));
    fireEvent.change(editor, { target: { value: '保存期间继续输入的内容' } });
    await waitFor(() => expect(readingNotes.get(readingTaskId)?.version).toBe(3), {
      timeout: 4000,
    });
    await waitFor(() =>
      expect(screen.getByRole('button', { name: '保存修改' })).not.toHaveClass('ant-btn-loading'),
    );
    expect(editor).toHaveValue('保存期间继续输入的内容');
    expect(screen.getByText('有尚未保存的修改')).toBeInTheDocument();
  });

  it('explicitly rechecks updated sources even after the previous task was full', async () => {
    readingTasks.get(readingTaskId)!.completion = 'FULL';
    readingNotes.get(readingTaskId)!.sourceStatus = 'CHANGED';
    const previous = readingNotes.get(readingTaskId)!.content;
    mount();
    fireEvent.click(await screen.findByRole('button', { name: '基于更新资料重新分析' }));
    await screen.findByText('排队中');
    expect(readingTasks.get(readingTaskId)?.roundNo).toBe(2);
    expect(readingNotes.get(readingTaskId)?.content).toBe(previous);
  });
});
