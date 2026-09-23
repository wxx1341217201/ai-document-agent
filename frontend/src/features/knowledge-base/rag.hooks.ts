import { useMutation } from '@tanstack/react-query';
import { ragApi } from '../../api/rag.api';
import type { RagQueryRequest } from '../../api/contracts';

/** Executes one non-streaming RAG request; its server state stays in TanStack Query. */
export function useRagQuery(knowledgeBaseId: number) {
  return useMutation({
    mutationFn: (input: RagQueryRequest) => ragApi.query(knowledgeBaseId, input),
  });
}
