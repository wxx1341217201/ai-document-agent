import { HttpResponse, http } from 'msw';
import type { CreateReadingTask } from '../../api/reading.api';
import { getDocument } from './fixtures';
import {
  mockReadingNote,
  mockReadingResult,
  mockReadingTask,
  readingNotes,
  readingRequests,
  readingResults,
  readingRevisions,
  readingTasks,
} from './reading-fixtures';

const root = '*/api/v1/reading/tasks';
const ok = (data: unknown, status = 200) =>
  HttpResponse.json(
    { code: 'SUCCESS', message: 'OK', data, traceId: 'reading-ui-fixture' },
    { status },
  );
const error = (status: number, message: string) =>
  HttpResponse.json({ code: `READING_${status}`, message, data: null }, { status });
function scoped(request: Request, id: string) {
  const task = readingTasks.get(id);
  return task &&
    task.knowledgeBaseId === Number(new URL(request.url).searchParams.get('knowledgeBaseId'))
    ? task
    : undefined;
}
function page<T>(values: T[], request: Request) {
  const query = new URL(request.url).searchParams;
  const index = Number(query.get('page') ?? 0);
  const size = Number(query.get('size') ?? 20);
  return {
    content: values.slice(index * size, (index + 1) * size),
    page: index,
    size,
    totalElements: values.length,
    totalPages: Math.ceil(values.length / size),
  };
}
export const readingHandlers = [
  http.get(root, ({ request }) => {
    const query = new URL(request.url).searchParams;
    return ok(
      page(
        [...readingTasks.values()]
          .filter(
            (t) =>
              t.knowledgeBaseId === Number(query.get('knowledgeBaseId')) &&
              (!query.get('sessionId') || t.sessionId === query.get('sessionId')),
          )
          .reverse(),
        request,
      ),
    );
  }),
  http.post(root, async ({ request }) => {
    const body = (await request.json()) as CreateReadingTask;
    const key = request.headers.get('Idempotency-Key') ?? crypto.randomUUID();
    const previous = readingRequests.get(key);
    const signature = JSON.stringify(body);
    if (previous)
      return previous.signature === signature
        ? ok(readingTasks.get(previous.taskId))
        : error(409, '请求内容已变化');
    const task = mockReadingTask();
    task.id = crypto.randomUUID();
    task.goal = body.goal;
    task.knowledgeBaseId = body.knowledgeBaseId;
    task.latestResultId = crypto.randomUUID();
    task.comprehensive = body.comprehensive;
    task.parentTaskId = body.parentTaskId ?? null;
    task.sessionId = body.parentTaskId
      ? (readingTasks.get(body.parentTaskId)?.sessionId ?? task.sessionId)
      : crypto.randomUUID();
    task.documents = body.documentIds.map((id) => ({
      id,
      name: getDocument(body.knowledgeBaseId, id)?.originalName ?? '示例资料.txt',
      fileSha256: 'a'.repeat(64),
      fingerprint: 'b'.repeat(64),
      chunks: 3,
    }));
    task.goals[0] = { ...task.goals[0], description: body.goal };
    readingTasks.set(task.id, task);
    readingRequests.set(key, { signature, taskId: task.id });
    const result = mockReadingResult(task);
    if (task.documents[0])
      result.sources = result.sources.map((s) => ({
        ...s,
        documentId: task.documents[0].id,
        documentName: task.documents[0].name,
      }));
    readingResults.set(task.latestResultId, result);
    readingNotes.set(task.id, {
      ...mockReadingNote(),
      taskId: task.id,
      content: '',
      version: 0,
      sources: [],
      adoptedResultId: null,
      pendingResultId: task.latestResultId,
    });
    readingRevisions.set(task.id, []);
    return ok(task, 202);
  }),
  http.get(`${root}/:id`, ({ request, params }) => {
    const task = scoped(request, String(params.id));
    return task ? ok(task) : error(404, '阅读任务不存在');
  }),
  ...(['continue', 'resume', 'cancel', 'refresh-sources'] as const).map((action) =>
    http.post(`${root}/:id/${action}`, async ({ request, params }) => {
      const task = scoped(request, String(params.id));
      if (!task) return error(404, '阅读任务不存在');
      const body = (await request.json()) as { expectedVersion: number; expectedRoundNo: number };
      if (
        body.expectedRoundNo !== task.roundNo ||
        (action !== 'cancel' && body.expectedVersion !== task.version)
      )
        return error(409, '任务状态已变化');
      task.status = action === 'cancel' ? 'CANCELLED' : 'PENDING';
      task.version++;
      if (action === 'continue' || action === 'refresh-sources') {
        task.roundNo++;
        task.usage = { ...task.usage, modelCalls: 0, toolCalls: 0, replans: 0 };
        if (action === 'refresh-sources') task.completion = 'NONE';
      }
      return ok(task, 202);
    }),
  ),
  http.post(`${root}/:id/scope`, async ({ request, params }) => {
    const task = scoped(request, String(params.id));
    if (!task) return error(404, '阅读任务不存在');
    const body = (await request.json()) as { documentIds: number[]; expectedVersion: number };
    if (body.expectedVersion !== task.version) return error(409, '任务状态已变化');
    task.documents = body.documentIds.map(
      (id) =>
        task.documents.find((d) => d.id === id) ?? {
          id,
          name: getDocument(task.knowledgeBaseId, id)?.originalName ?? '补充资料.txt',
          fileSha256: 'a'.repeat(64),
          fingerprint: 'b'.repeat(64),
          chunks: 1,
        },
    );
    task.roundNo++;
    task.version++;
    task.status = 'PENDING';
    return ok(task, 202);
  }),
  http.get(`${root}/:id/results`, ({ request, params }) => {
    const task = scoped(request, String(params.id));
    if (!task) return error(404, '阅读任务不存在');
    return ok(
      page(
        [...readingResults.values()]
          .filter((r) => r.taskId === task.id)
          .map((r) => ({
            id: r.id,
            roundNo: r.roundNo,
            completion: r.completion,
            createdAt: r.createdAt,
          }))
          .reverse(),
        request,
      ),
    );
  }),
  http.get(`${root}/:id/results/:resultId`, ({ request, params }) => {
    const task = scoped(request, String(params.id));
    const result = readingResults.get(String(params.resultId));
    return task && result?.taskId === task.id ? ok(result) : error(404, '结果不存在');
  }),
  http.get(`${root}/:id/note`, ({ request, params }) =>
    scoped(request, String(params.id))
      ? ok(readingNotes.get(String(params.id)))
      : error(404, '笔记不存在'),
  ),
  http.put(`${root}/:id/note`, async ({ request, params }) => {
    const task = scoped(request, String(params.id));
    if (!task) return error(404, '笔记不存在');
    const note = readingNotes.get(task.id)!;
    const body = (await request.json()) as {
      content: string;
      expectedVersion: number;
      sourceResultId?: string;
    };
    if (body.expectedVersion !== note.version) return error(409, '笔记版本已变化');
    readingRevisions.get(task.id)?.unshift({
      id: crypto.randomUUID(),
      revision: note.version,
      content: note.content,
      sources: structuredClone(note.sources),
      sourceResultId: note.adoptedResultId,
      createdAt: new Date().toISOString(),
    });
    note.content = body.content;
    note.version++;
    if (body.sourceResultId) {
      note.adoptedResultId = body.sourceResultId;
      note.pendingResultId = null;
    }
    return ok(note);
  }),
  http.get(`${root}/:id/note/revisions`, ({ request, params }) =>
    scoped(request, String(params.id))
      ? ok(page(readingRevisions.get(String(params.id)) ?? [], request))
      : error(404, '笔记不存在'),
  ),
  http.get(`${root}/:id/note/revisions/:revisionId`, ({ request, params }) => {
    if (!scoped(request, String(params.id))) return error(404, '笔记不存在');
    const found = readingRevisions.get(String(params.id))?.find((r) => r.id === params.revisionId);
    return found ? ok(found) : error(404, '修订不存在');
  }),
  http.get(`${root}/:id/note/export`, ({ request, params }) =>
    scoped(request, String(params.id))
      ? new HttpResponse(readingNotes.get(String(params.id))?.content, {
          headers: {
            'Content-Type': 'text/markdown; charset=utf-8',
            'Content-Disposition': 'attachment; filename="reading-note.md"',
          },
        })
      : error(404, '笔记不存在'),
  ),
];
