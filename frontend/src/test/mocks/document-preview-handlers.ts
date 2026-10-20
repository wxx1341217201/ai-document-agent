import { HttpResponse, http } from 'msw';
import { getDocument, getUploadedFile } from './fixtures';
import { createMockPdf, mockDocumentChunks } from './document-preview-fixtures';

const base = '*/api/v1/knowledge-bases/:kbId/documents/:documentId';
const success = (data: unknown) =>
  HttpResponse.json({ code: 'SUCCESS', message: 'OK', data, traceId: 'mock-preview' });
const missing = () =>
  HttpResponse.json(
    { code: 'DOCUMENT_NOT_FOUND', message: '文档不存在', data: null },
    { status: 404 },
  );

/** All these REST endpoints are absent from the real DocumentController. */
export const documentPreviewHandlers = [
  http.get(`${base}/download`, ({ params }) => {
    const document = getDocument(Number(params.kbId), Number(params.documentId));
    if (!document) return missing();
    const uploaded = getUploadedFile(document.id);
    return new HttpResponse(
      uploaded ?? (document.extension === 'pdf' ? createMockPdf() : 'Mock 抽取文本'),
      {
        headers: {
          'Content-Type': document.contentType,
          'Content-Disposition': 'attachment; filename="mock-document"',
        },
      },
    );
  }),
  http.get(`${base}/chunks`, ({ params, request }) => {
    const document = getDocument(Number(params.kbId), Number(params.documentId));
    if (!document) return missing();
    const search = new URL(request.url).searchParams;
    const page = Number(search.get('page') ?? 0);
    const size = Number(search.get('size') ?? 50);
    const chunks = document.status === 'READY' ? mockDocumentChunks : [];
    return success({
      content: chunks.slice(page * size, (page + 1) * size),
      page,
      size,
      totalElements: chunks.length,
    });
  }),
  http.get(`${base}/chunks/:chunkId/context`, ({ params }) => {
    if (!getDocument(Number(params.kbId), Number(params.documentId))) return missing();
    const index = mockDocumentChunks.findIndex((chunk) => chunk.chunkId === Number(params.chunkId));
    // Array shape is a Mock convention only; §8.4 does not specify a REST response DTO.
    return success(index < 0 ? [] : mockDocumentChunks.slice(Math.max(0, index - 1), index + 2));
  }),
];
