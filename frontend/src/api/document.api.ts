import { apiRequest, apiUrl } from './client';
import type {
  DocumentRecord,
  IngestionJobResponse,
  PageResponse,
  PaginationParams,
} from './contracts';

function resourcePath(knowledgeBaseId: number) {
  return `/knowledge-bases/${knowledgeBaseId}/documents`;
}

export const documentApi = {
  list(knowledgeBaseId: number, params: PaginationParams) {
    return apiRequest<PageResponse<DocumentRecord>>({
      method: 'GET',
      url: resourcePath(knowledgeBaseId),
      params,
    });
  },

  get(knowledgeBaseId: number, documentId: number) {
    return apiRequest<DocumentRecord>({
      method: 'GET',
      url: `${resourcePath(knowledgeBaseId)}/${documentId}`,
    });
  },

  upload(knowledgeBaseId: number, file: File, onProgress?: (percent: number) => void) {
    const formData = new FormData();
    formData.append('file', file);

    return apiRequest<DocumentRecord>({
      method: 'POST',
      url: resourcePath(knowledgeBaseId),
      data: formData,
      onUploadProgress: (event) => {
        if (event.total) {
          onProgress?.(Math.round((event.loaded / event.total) * 100));
        }
      },
    });
  },

  retry(knowledgeBaseId: number, documentId: number) {
    return apiRequest<IngestionJobResponse>({
      method: 'POST',
      url: `${resourcePath(knowledgeBaseId)}/${documentId}/ingestion/retry`,
    });
  },

  delete(knowledgeBaseId: number, documentId: number) {
    return apiRequest<void>({
      method: 'DELETE',
      url: `${resourcePath(knowledgeBaseId)}/${documentId}`,
    });
  },

  downloadUrl(knowledgeBaseId: number, documentId: number) {
    return apiUrl(`${resourcePath(knowledgeBaseId)}/${documentId}/download`);
  },
};
