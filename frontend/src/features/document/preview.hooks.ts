import { useQuery } from '@tanstack/react-query';
import {
  citationContextMockEnabled,
  documentPreviewApi,
  parseMockCitationContext,
} from '../../api/document-preview.api';
import type { PaginationParams, RagCitation } from '../../api/contracts';
import { queryKeys } from '../../api/query-keys';
import { isPositiveId } from '../../api/progress.contracts';

export function useDocumentChunks(
  kbId: number,
  documentId: number,
  params: PaginationParams,
  enabled: boolean,
) {
  return useQuery({
    queryKey: queryKeys.documents.chunks(kbId, documentId, params),
    queryFn: ({ signal }) => documentPreviewApi.chunks(kbId, documentId, params, signal),
    enabled,
  });
}

export function useCitationContext(kbId: number, citation?: RagCitation) {
  return useQuery({
    queryKey: queryKeys.documents.context(
      kbId,
      citation?.documentId ?? -1,
      citation?.chunkId ?? -1,
    ),
    queryFn: async ({ signal }) =>
      parseMockCitationContext(
        await documentPreviewApi.context(kbId, citation!.documentId, citation!.chunkId, signal),
      ),
    enabled:
      citationContextMockEnabled &&
      isPositiveId(kbId) &&
      isPositiveId(citation?.documentId) &&
      isPositiveId(citation?.chunkId),
  });
}
