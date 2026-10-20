import type { ExecutionResponse, RagCitation } from '../../api/contracts';
import type { ChatMessageData } from './ChatMessage';

/** Only final answer fields are exposed. Never spread arbitrary execution JSON into Chat UI. */
export function executionMessage(execution: ExecutionResponse): ChatMessageData | undefined {
  const result = execution.result;
  if (
    execution.status !== 'SUCCEEDED' ||
    !isRecord(result) ||
    result.status !== 'COMPLETED' ||
    typeof result.answer !== 'string' ||
    !result.answer.trim() ||
    /<\/?think\b/i.test(result.answer)
  )
    return undefined;

  const citations = Array.isArray(result.citations)
    ? result.citations.filter(
        (value): value is RagCitation & { knowledgeBaseId: number } =>
          isRecord(value) &&
          value.knowledgeBaseId === execution.knowledgeBaseId &&
          typeof value.citationId === 'string' &&
          /^C[1-9]\d*$/.test(value.citationId) &&
          isPositiveId(value.documentId) &&
          isPositiveId(value.chunkId) &&
          typeof value.documentName === 'string' &&
          typeof value.quote === 'string' &&
          ((value.pageFrom === null && value.pageTo === null) ||
            (isPositiveId(value.pageFrom) &&
              isPositiveId(value.pageTo) &&
              value.pageTo >= value.pageFrom)),
      )
    : [];
  const usage = isRecord(result.usage) ? result.usage : undefined;
  return {
    role: 'ASSISTANT',
    content: result.answer,
    citations,
    modelName: typeof result.modelName === 'string' ? result.modelName : null,
    tokenUsage: usage
      ? {
          promptTokens: tokenCount(usage.promptTokens),
          completionTokens: tokenCount(usage.completionTokens),
          totalTokens: tokenCount(usage.totalTokens),
        }
      : null,
    createdAt: execution.updatedAt,
  };
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null;
}

function isPositiveId(value: unknown): value is number {
  return typeof value === 'number' && Number.isSafeInteger(value) && value > 0;
}

function tokenCount(value: unknown): number | null {
  return typeof value === 'number' && Number.isSafeInteger(value) && value >= 0 ? value : null;
}
