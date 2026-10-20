import { apiRequest, apiUrl } from './client';
import type { PageResponse } from './contracts';

export interface ReadingDocument {
  id: number;
  name: string;
  fileSha256: string;
  fingerprint: string;
  chunks: number;
  limitations?: string[];
}
export interface ReadingGoal {
  id: string;
  description: string;
  query: string;
  engine: string;
  covered: boolean;
}
export interface ReadingSource {
  citationId: string;
  documentId: number;
  documentName: string;
  documentVersion: string;
  chunkId: number;
  chunkIndex: number;
  contentHash: string;
  offset: number;
  text: string;
  pageFrom: number | null;
  pageTo: number | null;
  sectionTitle: string | null;
}
export interface ReadingFinding {
  id: string;
  goalId: string;
  kind: 'FACT' | 'COMMENTARY' | 'INFERENCE' | 'DISPUTE';
  text: string;
  supports: { citationId: string; quote: string }[];
}
export interface ReadingCoverage {
  documentId: number;
  name: string;
  reviewedChunks: number;
  totalChunks: number;
}
export interface ReadingUsage {
  modelCalls: number;
  toolCalls: number;
  replans: number;
  promptTokens: number;
  completionTokens: number;
}
export interface ReadingStage {
  engine: string;
  operation: string;
  status: string;
  message: string;
  at: string;
}
export type ReadingStatus = 'PENDING' | 'RUNNING' | 'INTERRUPTED' | 'SUCCEEDED' | 'CANCELLED';
export type ReadingCompletion = 'FULL' | 'PARTIAL' | 'NONE';
export interface ReadingTaskSummary {
  id: string;
  knowledgeBaseId: number;
  sessionId: string;
  goal: string;
  status: ReadingStatus;
  completion: ReadingCompletion;
  roundNo: number;
  latestResultId: string | null;
  createdAt: string;
  updatedAt: string;
}
export interface ReadingTask extends ReadingTaskSummary {
  parentTaskId: string | null;
  version: number;
  documents: ReadingDocument[];
  goals: ReadingGoal[];
  coverage: ReadingCoverage[];
  stages: ReadingStage[];
  usage: ReadingUsage;
  comprehensive: boolean;
  errorMessage: string | null;
  deadlineAt: string | null;
}
export interface ReadingResult {
  id: string;
  taskId: string;
  roundNo: number;
  completion: ReadingCompletion;
  markdown: string;
  findings: ReadingFinding[];
  sources: ReadingSource[];
  documents: ReadingDocument[];
  goals: ReadingGoal[];
  gaps: string[];
  coverage: ReadingCoverage[];
  usage: ReadingUsage;
  createdAt: string;
}
export interface ReadingNote {
  id: string;
  taskId: string;
  content: string;
  version: number;
  adoptedResultId: string | null;
  pendingResultId: string | null;
  sources: ReadingSource[];
  sourceStatus: string;
  updatedAt: string;
}
export interface ReadingResultSummary {
  id: string;
  roundNo: number;
  completion: ReadingCompletion;
  createdAt: string;
}
export interface ReadingRevisionSummary {
  id: string;
  revision: number;
  sourceResultId: string | null;
  createdAt: string;
}
export interface ReadingRevision extends ReadingRevisionSummary {
  content: string;
  sources: ReadingSource[];
}
export interface CreateReadingTask {
  knowledgeBaseId: number;
  goal: string;
  documentIds: number[];
  comprehensive: boolean;
  parentTaskId?: string;
}

const path = '/reading/tasks';
const scoped = (knowledgeBaseId: number) => ({ knowledgeBaseId });
export const readingApi = {
  list(knowledgeBaseId: number, page = 0, size = 20, sessionId?: string) {
    return apiRequest<PageResponse<ReadingTaskSummary>>({
      url: path,
      params: { knowledgeBaseId, page, size, sessionId },
    });
  },
  create(input: CreateReadingTask, key: string) {
    return apiRequest<ReadingTask>({
      method: 'POST',
      url: path,
      data: input,
      headers: { 'Idempotency-Key': key },
    });
  },
  get(knowledgeBaseId: number, id: string) {
    return apiRequest<ReadingTask>({ url: `${path}/${id}`, params: scoped(knowledgeBaseId) });
  },
  action(
    knowledgeBaseId: number,
    task: ReadingTask,
    action: 'continue' | 'resume' | 'cancel' | 'refresh-sources',
  ) {
    return apiRequest<ReadingTask>({
      method: 'POST',
      url: `${path}/${task.id}/${action}`,
      params: scoped(knowledgeBaseId),
      data: { expectedVersion: task.version, expectedRoundNo: task.roundNo },
    });
  },
  expand(knowledgeBaseId: number, task: ReadingTask, documentIds: number[]) {
    return apiRequest<ReadingTask>({
      method: 'POST',
      url: `${path}/${task.id}/scope`,
      params: scoped(knowledgeBaseId),
      data: { expectedVersion: task.version, documentIds },
    });
  },
  result(knowledgeBaseId: number, id: string, resultId: string) {
    return apiRequest<ReadingResult>({
      url: `${path}/${id}/results/${resultId}`,
      params: scoped(knowledgeBaseId),
    });
  },
  results(knowledgeBaseId: number, id: string, page = 0) {
    return apiRequest<PageResponse<ReadingResultSummary>>({
      url: `${path}/${id}/results`,
      params: { knowledgeBaseId, page, size: 20 },
    });
  },
  note(knowledgeBaseId: number, id: string) {
    return apiRequest<ReadingNote>({ url: `${path}/${id}/note`, params: scoped(knowledgeBaseId) });
  },
  edit(
    knowledgeBaseId: number,
    id: string,
    content: string,
    expectedVersion: number,
    sourceResultId?: string,
  ) {
    return apiRequest<ReadingNote>({
      method: 'PUT',
      url: `${path}/${id}/note`,
      params: scoped(knowledgeBaseId),
      data: { content, expectedVersion, sourceResultId },
    });
  },
  revisions(knowledgeBaseId: number, id: string, page = 0) {
    return apiRequest<PageResponse<ReadingRevisionSummary>>({
      url: `${path}/${id}/note/revisions`,
      params: { knowledgeBaseId, page, size: 20 },
    });
  },
  revision(knowledgeBaseId: number, id: string, revisionId: string) {
    return apiRequest<ReadingRevision>({
      url: `${path}/${id}/note/revisions/${revisionId}`,
      params: scoped(knowledgeBaseId),
    });
  },
  exportUrl(knowledgeBaseId: number, id: string) {
    return apiUrl(`${path}/${id}/note/export?knowledgeBaseId=${knowledgeBaseId}`);
  },
};
