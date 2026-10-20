import type { ReadingNote, ReadingResult, ReadingSource, ReadingTask } from '../../api/reading.api';

export const readingTaskId = '77777777-7777-4777-8777-777777777777';
export const readingResultId = '88888888-8888-4888-8888-888888888888';
export const readingSessionId = '99999999-9999-4999-8999-999999999999';
export const readingSource: ReadingSource = {
  citationId: 'C1',
  documentId: 101,
  documentName: 'catalyst-paper.pdf',
  documentVersion: 'b'.repeat(64),
  chunkId: 1001,
  chunkIndex: 0,
  contentHash: 'c'.repeat(64),
  offset: 0,
  text: '页面流程测试资料：观察组的响应高于对照组。该文本仅用于软件流程验证。',
  pageFrom: 1,
  pageTo: 1,
  sectionTitle: '示例记录',
};
export function mockReadingTask(): ReadingTask {
  return {
    id: readingTaskId,
    knowledgeBaseId: 1,
    sessionId: readingSessionId,
    parentTaskId: null,
    goal: '整理示例记录中的观察与解释',
    status: 'SUCCEEDED',
    completion: 'PARTIAL',
    roundNo: 1,
    version: 4,
    latestResultId: readingResultId,
    documents: [
      {
        id: 101,
        name: 'catalyst-paper.pdf',
        fileSha256: 'a'.repeat(64),
        fingerprint: 'b'.repeat(64),
        chunks: 3,
      },
    ],
    goals: [
      {
        id: 'G0',
        description: '整理示例记录中的观察与解释',
        query: '观察 响应',
        engine: 'RETRIEVAL_FIRST',
        covered: false,
      },
      {
        id: 'G1',
        description: '核对观察结果',
        query: '观察组 对照组',
        engine: 'RETRIEVAL_FIRST',
        covered: true,
      },
    ],
    coverage: [{ documentId: 101, name: 'catalyst-paper.pdf', reviewedChunks: 0, totalChunks: 3 }],
    stages: [
      {
        engine: 'RETRIEVAL_FIRST',
        operation: 'search',
        status: 'SUCCEEDED',
        message: '取得范围内的示例片段',
        at: '2026-10-20T02:00:00Z',
      },
      {
        engine: 'REACT',
        operation: 'context',
        status: 'SUCCEEDED',
        message: '已核对示例上下文',
        at: '2026-10-20T02:00:05Z',
      },
      {
        engine: 'PLAN_EXECUTE_REFLECT',
        operation: 'REFLECT',
        status: 'SUCCEEDED',
        message: '阶段结论已核验，保留解释缺口',
        at: '2026-10-20T02:00:10Z',
      },
    ],
    usage: { modelCalls: 4, toolCalls: 4, replans: 0, promptTokens: 150, completionTokens: 80 },
    comprehensive: false,
    errorMessage: null,
    deadlineAt: null,
    createdAt: '2026-10-20T02:00:00Z',
    updatedAt: '2026-10-20T02:00:10Z',
  };
}
export function mockReadingResult(task = mockReadingTask()): ReadingResult {
  return {
    id: task.latestResultId ?? readingResultId,
    taskId: task.id,
    roundNo: task.roundNo,
    completion: task.completion,
    markdown:
      '# 阅读笔记\n\n## 原文事实\n\n观察组的响应高于对照组。[C1]\n\n## 尚未解决的问题\n\n资料尚不足以解释差异的原因。',
    findings: [
      {
        id: 'F1',
        goalId: 'G1',
        kind: 'FACT',
        text: '观察组的响应高于对照组。',
        supports: [{ citationId: 'C1', quote: '观察组的响应高于对照组。' }],
      },
    ],
    sources: [readingSource],
    documents: task.documents,
    goals: task.goals,
    gaps: ['资料尚不足以解释差异的原因。'],
    coverage: task.coverage,
    usage: task.usage,
    createdAt: task.updatedAt,
  };
}
export function mockReadingNote(): ReadingNote {
  return {
    id: 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa',
    taskId: readingTaskId,
    content: '# 我的阅读笔记\n\n已保存的人工思考。[C1]',
    version: 2,
    adoptedResultId: 'bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb',
    pendingResultId: readingResultId,
    sources: [readingSource],
    sourceStatus: 'CURRENT',
    updatedAt: '2026-10-20T02:00:00Z',
  };
}

export const readingTasks = new Map<string, ReadingTask>();
export const readingResults = new Map<string, ReadingResult>();
export const readingNotes = new Map<string, ReadingNote>();
export const readingRevisions = new Map<
  string,
  {
    id: string;
    revision: number;
    content: string;
    sources: ReadingSource[];
    sourceResultId: string | null;
    createdAt: string;
  }[]
>();
export const readingRequests = new Map<string, { signature: string; taskId: string }>();
export function resetReadingFixtures() {
  readingTasks.clear();
  readingResults.clear();
  readingNotes.clear();
  readingRevisions.clear();
  readingRequests.clear();
  const task = mockReadingTask();
  readingTasks.set(task.id, structuredClone(task));
  readingResults.set(readingResultId, mockReadingResult(task));
  readingNotes.set(task.id, mockReadingNote());
  readingRevisions.set(task.id, []);
}
