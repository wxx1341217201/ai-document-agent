import { HttpResponse, http } from 'msw';
import type { ApiResponse, PageResponse } from '../../api/contracts';
import {
  addDocument,
  addKnowledgeBase,
  getDocument,
  getDocuments,
  getKnowledgeBase,
  getKnowledgeBases,
  removeDocument,
  removeKnowledgeBase,
  updateDocument,
  updateKnowledgeBase,
} from './fixtures';

const API_PATH = '*/api/v1';
const traceId = 'mock-trace-id';

export const handlers = [
  http.get(`${API_PATH}/knowledge-bases`, ({ request }) => {
    return success(page(getKnowledgeBases(), request));
  }),

  http.post(`${API_PATH}/knowledge-bases`, async ({ request }) => {
    const input = (await request.json()) as { name?: string; description?: string };
    const name = input.name?.trim();
    if (!name) {
      return failure(400, 'VALIDATION_ERROR', '参数校验失败', [
        { field: 'name', message: '名称不能为空' },
      ]);
    }
    if (getKnowledgeBases().some((knowledgeBase) => knowledgeBase.name === name)) {
      return failure(409, 'KNOWLEDGE_BASE_NAME_CONFLICT', '知识库名称已存在');
    }
    return success(addKnowledgeBase({ name, description: input.description }), 201);
  }),

  http.get(`${API_PATH}/knowledge-bases/:knowledgeBaseId`, ({ params }) => {
    const knowledgeBase = getKnowledgeBase(numberParam(params.knowledgeBaseId));
    return knowledgeBase
      ? success(knowledgeBase)
      : failure(404, 'KNOWLEDGE_BASE_NOT_FOUND', '知识库不存在');
  }),

  http.put(`${API_PATH}/knowledge-bases/:knowledgeBaseId`, async ({ params, request }) => {
    const knowledgeBaseId = numberParam(params.knowledgeBaseId);
    const input = (await request.json()) as { name?: string; description?: string };
    const name = input.name?.trim();
    if (!name) {
      return failure(400, 'VALIDATION_ERROR', '参数校验失败', [
        { field: 'name', message: '名称不能为空' },
      ]);
    }
    if (
      getKnowledgeBases().some(
        (knowledgeBase) => knowledgeBase.id !== knowledgeBaseId && knowledgeBase.name === name,
      )
    ) {
      return failure(409, 'KNOWLEDGE_BASE_NAME_CONFLICT', '知识库名称已存在');
    }
    const knowledgeBase = updateKnowledgeBase(knowledgeBaseId, {
      name,
      description: input.description,
    });
    return knowledgeBase
      ? success(knowledgeBase)
      : failure(404, 'KNOWLEDGE_BASE_NOT_FOUND', '知识库不存在');
  }),

  http.delete(`${API_PATH}/knowledge-bases/:knowledgeBaseId`, ({ params }) => {
    const knowledgeBaseId = numberParam(params.knowledgeBaseId);
    if (!getKnowledgeBase(knowledgeBaseId)) {
      return failure(404, 'KNOWLEDGE_BASE_NOT_FOUND', '知识库不存在');
    }
    if (getDocuments(knowledgeBaseId).length > 0) {
      return failure(409, 'KNOWLEDGE_BASE_NOT_EMPTY', '知识库仍包含文档，无法删除');
    }
    removeKnowledgeBase(knowledgeBaseId);
    return success(null);
  }),

  http.get(`${API_PATH}/knowledge-bases/:knowledgeBaseId/documents`, ({ params, request }) => {
    const knowledgeBaseId = numberParam(params.knowledgeBaseId);
    if (!getKnowledgeBase(knowledgeBaseId)) {
      return failure(404, 'KNOWLEDGE_BASE_NOT_FOUND', '知识库不存在');
    }
    return success(page(getDocuments(knowledgeBaseId), request));
  }),

  http.post(
    `${API_PATH}/knowledge-bases/:knowledgeBaseId/documents`,
    async ({ params, request }) => {
      const knowledgeBaseId = numberParam(params.knowledgeBaseId);
      if (!getKnowledgeBase(knowledgeBaseId)) {
        return failure(404, 'KNOWLEDGE_BASE_NOT_FOUND', '知识库不存在');
      }
      const formData = await request.formData();
      const file = formData.get('file');
      if (!(file instanceof File)) {
        return failure(400, 'BAD_REQUEST', '缺少文件');
      }
      if (getDocuments(knowledgeBaseId).some((document) => document.originalName === file.name)) {
        return failure(409, 'DUPLICATE_DOCUMENT', '该知识库已存在相同内容的文档');
      }
      return success(addDocument(knowledgeBaseId, file), 201);
    },
  ),

  http.post(
    `${API_PATH}/knowledge-bases/:knowledgeBaseId/documents/:documentId/ingestion/retry`,
    ({ params }) => {
      const knowledgeBaseId = numberParam(params.knowledgeBaseId);
      const documentId = numberParam(params.documentId);
      const document = getDocument(knowledgeBaseId, documentId);
      if (!document) {
        return failure(404, 'DOCUMENT_NOT_FOUND', '文档不存在');
      }
      if (document.status !== 'FAILED') {
        return failure(409, 'DOCUMENT_STATE_CONFLICT', '当前文档状态不允许重新处理');
      }
      updateDocument(knowledgeBaseId, documentId, {
        status: 'RETRYING',
        errorCode: null,
        errorMessage: null,
      });
      return success({
        jobId: `mock-job-${documentId}`,
        documentId,
        knowledgeBaseId,
        status: 'RETRYING',
        attempt: 2,
        totalBatchCount: 1,
        completedBatchCount: 0,
        failedBatchCount: 0,
        errorCode: null,
        errorMessage: null,
        enqueuedAt: '2026-09-20T10:45:00',
        startedAt: null,
        completedAt: null,
      });
    },
  ),

  http.get(
    `${API_PATH}/knowledge-bases/:knowledgeBaseId/documents/:documentId/download`,
    ({ params }) => {
      const document = getDocument(
        numberParam(params.knowledgeBaseId),
        numberParam(params.documentId),
      );
      if (!document) {
        return failure(404, 'DOCUMENT_NOT_FOUND', '文档不存在');
      }
      return new HttpResponse('Mock document content', {
        headers: {
          'Content-Type': document.contentType,
          'Content-Disposition': `attachment; filename="${document.originalName}"`,
        },
      });
    },
  ),

  http.get(`${API_PATH}/knowledge-bases/:knowledgeBaseId/documents/:documentId`, ({ params }) => {
    const document = getDocument(
      numberParam(params.knowledgeBaseId),
      numberParam(params.documentId),
    );
    return document ? success(document) : failure(404, 'DOCUMENT_NOT_FOUND', '文档不存在');
  }),

  http.delete(
    `${API_PATH}/knowledge-bases/:knowledgeBaseId/documents/:documentId`,
    ({ params }) => {
      const knowledgeBaseId = numberParam(params.knowledgeBaseId);
      const documentId = numberParam(params.documentId);
      const document = getDocument(knowledgeBaseId, documentId);
      if (!document) {
        return failure(404, 'DOCUMENT_NOT_FOUND', '文档不存在');
      }
      removeDocument(knowledgeBaseId, documentId);
      return success(null);
    },
  ),

  // FE05 test/demo mock: production data is supplied by the backend query endpoint.
  http.post(`${API_PATH}/knowledge-bases/:knowledgeBaseId/query`, async ({ params, request }) => {
    const knowledgeBaseId = numberParam(params.knowledgeBaseId);
    if (!getKnowledgeBase(knowledgeBaseId)) {
      return failure(404, 'KNOWLEDGE_BASE_NOT_FOUND', '知识库不存在');
    }

    const input = (await request.json()) as {
      question?: unknown;
      topK?: unknown;
      rerank?: unknown;
      stream?: unknown;
    };
    const question = typeof input.question === 'string' ? input.question.trim() : '';
    if (!question) {
      return failure(400, 'VALIDATION_ERROR', '参数校验失败', [
        { field: 'question', message: '问题不能为空' },
      ]);
    }
    if (
      input.topK !== undefined &&
      (typeof input.topK !== 'number' ||
        !Number.isInteger(input.topK) ||
        input.topK < 1 ||
        input.topK > 20)
    ) {
      return failure(400, 'VALIDATION_ERROR', '参数校验失败', [
        { field: 'topK', message: 'topK必须在1到20之间' },
      ]);
    }
    if (input.stream === true) {
      return failure(400, 'VALIDATION_ERROR', '参数校验失败', [
        { field: 'stream', message: '当前接口暂不支持流式输出' },
      ]);
    }

    return success({
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
    });
  }),
];

function success<T>(data: T, status = 200) {
  const body: ApiResponse<T> = {
    code: 'SUCCESS',
    message: 'OK',
    data,
    traceId,
  };
  return HttpResponse.json(body, { status });
}

function failure(status: number, code: string, message: string, data: unknown = null) {
  return HttpResponse.json({ code, message, data, traceId }, { status });
}

function page<T>(items: T[], request: Request): PageResponse<T> {
  const url = new URL(request.url);
  const pageNumber = Math.max(0, Number(url.searchParams.get('page') ?? 0) || 0);
  const size = Math.max(1, Number(url.searchParams.get('size') ?? 20) || 20);
  const totalElements = items.length;
  return {
    content: items.slice(pageNumber * size, pageNumber * size + size),
    page: pageNumber,
    size,
    totalElements,
    totalPages: totalElements === 0 ? 0 : Math.ceil(totalElements / size),
  };
}

function numberParam(value: readonly string[] | string | undefined): number {
  const rawValue = Array.isArray(value) ? value[0] : value;
  return Number(rawValue);
}
