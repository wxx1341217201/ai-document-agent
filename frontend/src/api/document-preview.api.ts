import { ApiClientError, apiClient, apiRequest, normalizeApiError } from './client';
import type { DocumentChunk, DocumentChunkPage, PaginationParams } from './contracts';
import { isPositiveId, isRecord } from './progress.contracts';

const path = (kbId: number, documentId: number) =>
  `/knowledge-bases/${kbId}/documents/${documentId}`;

// §8.4 has no response DTO and the REST controller is absent. The array of §8.3
// chunks used in tests is a Mock convention, never an asserted production contract.
export const citationContextMockEnabled =
  import.meta.env.VITE_USE_MSW === 'true' ||
  import.meta.env.MODE === 'test' ||
  import.meta.env.MODE === 'e2e';

export const documentPreviewApi = {
  async chunks(kbId: number, documentId: number, params: PaginationParams, signal?: AbortSignal) {
    const data = await apiRequest<unknown>({
      method: 'GET',
      url: `${path(kbId, documentId)}/chunks`,
      params,
      signal,
    });
    if (
      !isRecord(data) ||
      !Array.isArray(data.content) ||
      data.page !== params.page ||
      data.size !== params.size ||
      !Number.isSafeInteger(data.totalElements) ||
      Number(data.totalElements) < 0 ||
      !data.content.every(isDocumentChunk)
    )
      throw invalidChunkResponse();
    return data as unknown as DocumentChunkPage;
  },

  /** Keep the undefined REST DTO unknown until the backend publishes its contract. */
  context(kbId: number, documentId: number, chunkId: number, signal?: AbortSignal) {
    return apiRequest<unknown>({
      method: 'GET',
      url: `${path(kbId, documentId)}/chunks/${chunkId}/context`,
      params: { before: 1, after: 1 },
      signal,
    });
  },

  async file(kbId: number, documentId: number, signal?: AbortSignal): Promise<Blob> {
    try {
      const response = await apiClient.get<Blob>(`${path(kbId, documentId)}/download`, {
        responseType: 'blob',
        signal,
        headers: { Accept: 'application/octet-stream, application/pdf' },
      });
      if (!(response.data instanceof Blob) || response.data.size === 0) {
        throw new ApiClientError({ code: 'EMPTY_FILE', message: '服务端未返回有效文件。' });
      }
      return response.data;
    } catch (error) {
      // Blob error bodies are not displayed; normalization emits a safe HTTP fallback.
      throw new ApiClientError(normalizeApiError(error));
    }
  },

  async pdf(kbId: number, documentId: number, signal?: AbortSignal) {
    const file = await documentPreviewApi.file(kbId, documentId, signal);
    const prefix = await blobText(file.slice(0, 5));
    if (prefix !== '%PDF-')
      throw new ApiClientError({
        code: 'INVALID_PDF',
        message: '下载内容不是有效 PDF，无法预览。',
      });
    // A verified PDF MIME prevents embedding a server HTML/error response as active content.
    return new Blob([file], { type: 'application/pdf' });
  },
};

export function parseMockCitationContext(data: unknown): DocumentChunk[] {
  if (!Array.isArray(data) || !data.every(isDocumentChunk)) throw invalidChunkResponse();
  return data;
}

function isDocumentChunk(value: unknown): value is DocumentChunk {
  if (!isRecord(value)) return false;
  return (
    isPositiveId(value.chunkId) &&
    Number.isSafeInteger(value.chunkIndex) &&
    Number(value.chunkIndex) >= 0 &&
    typeof value.content === 'string' &&
    (value.sectionTitle === null || typeof value.sectionTitle === 'string') &&
    ((value.pageFrom === null && value.pageTo === null) ||
      (isPositiveId(value.pageFrom) &&
        isPositiveId(value.pageTo) &&
        value.pageTo >= value.pageFrom))
  );
}

function invalidChunkResponse() {
  return new ApiClientError({
    code: 'INVALID_API_RESPONSE',
    message: '服务端返回的文档片段不符合已知契约。',
  });
}

function blobText(blob: Blob): Promise<string> {
  if (typeof blob.text === 'function') return blob.text();
  return new Promise((resolve, reject) => {
    const reader = new FileReader();
    reader.onload = () => resolve(String(reader.result));
    reader.onerror = () =>
      reject(new ApiClientError({ code: 'FILE_READ_ERROR', message: '无法读取文件内容。' }));
    reader.readAsText(blob);
  });
}
