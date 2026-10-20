/** Mirrors the backend response envelope. `timestamp` is optional for compatibility with the current API. */
export interface ApiResponse<T> {
  code: string;
  message: string;
  data: T;
  traceId?: string;
  timestamp?: string;
}

export interface PageResponse<T> {
  content: T[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
  first?: boolean;
  last?: boolean;
}

export interface FieldValidationError {
  field: string;
  message: string;
}

export interface ApiError {
  httpStatus?: number;
  code: string;
  message: string;
  traceId?: string;
  fieldErrors?: Record<string, string>;
}

export interface KnowledgeBase {
  id: number;
  name: string;
  description: string | null;
  createdAt: string;
  updatedAt: string;
}

export interface UpsertKnowledgeBaseRequest {
  name: string;
  description?: string;
}

export type DocumentStatus =
  'UPLOADED' | 'QUEUED' | 'PROCESSING' | 'RETRYING' | 'READY' | 'FAILED' | 'DELETED';

export interface DocumentRecord {
  id: number;
  knowledgeBaseId: number;
  originalName: string;
  contentType: string;
  extension: string;
  sizeBytes: number;
  sha256: string;
  status: DocumentStatus;
  errorCode: string | null;
  errorMessage: string | null;
  createdAt: string;
  updatedAt: string;
}

/** Dashboard summaries contain only the fields rendered by the recent lists. */
export type RecentKnowledgeBase = Pick<KnowledgeBase, 'id' | 'name' | 'description' | 'updatedAt'>;
export type RecentDocument = Pick<
  DocumentRecord,
  'id' | 'knowledgeBaseId' | 'originalName' | 'extension' | 'status' | 'updatedAt'
>;

export type IngestionJobStatus = Exclude<DocumentStatus, 'DELETED'>;

/** Actual response from the backend's ingestion retry endpoint. */
export interface IngestionJobResponse {
  jobId: string;
  documentId: number;
  knowledgeBaseId: number;
  status: IngestionJobStatus;
  attempt: number;
  totalBatchCount: number;
  completedBatchCount: number;
  failedBatchCount: number;
  errorCode: string | null;
  errorMessage: string | null;
  enqueuedAt: string | null;
  startedAt: string | null;
  completedAt: string | null;
}

export interface PaginationParams {
  page: number;
  size: number;
}

/** Input accepted by the non-streaming knowledge-base query endpoint. */
export interface RagQueryRequest {
  question: string;
  topK: number;
  rerank: boolean;
}

/** A verified source reference returned with one RAG answer. */
export interface RagCitation {
  citationId: string;
  documentId: number;
  documentName: string;
  chunkId: number;
  pageFrom: number | null;
  pageTo: number | null;
  quote: string;
}

/** Retrieval health summary returned by the backend with a RAG answer. */
export interface RagRetrievalSummary {
  degraded: boolean;
  candidateCount: number;
}

export interface RagQueryResponse {
  answer: string;
  citations: RagCitation[];
  retrieval: RagRetrievalSummary;
}

/** ConversationController DTOs. RAG is retained for existing backend conversations. */
export type ConversationAgentMode = 'RAG' | 'RETRIEVAL_FIRST' | 'REACT' | 'PLAN_EXECUTE_REFLECT';

export interface Conversation {
  id: number;
  knowledgeBaseId: number;
  title: string;
  agentMode: ConversationAgentMode;
  createdAt: string;
  updatedAt: string;
}

export interface CreateConversationRequest {
  title?: string;
  agentMode: ConversationAgentMode;
}

export interface ConversationTokenUsage {
  promptTokens: number | null;
  completionTokens: number | null;
  totalTokens: number | null;
}

export interface ConversationMessage {
  id: number;
  conversationId: number;
  sequenceNo: number;
  role: 'USER' | 'ASSISTANT';
  content: string;
  citations: RagCitation[];
  modelName: string | null;
  tokenUsage: ConversationTokenUsage | null;
  createdAt: string;
}

export interface SendConversationMessageRequest {
  content: string;
}

export interface SendConversationMessageResponse {
  userMessage: ConversationMessage;
  assistantMessage: ConversationMessage;
}

/** ExecutionController uses AgentMode, which excludes the legacy Conversation RAG mode. */
export type AgentMode = Exclude<ConversationAgentMode, 'RAG'>;

export type ExecutionStatus =
  'PENDING' | 'RUNNING' | 'PAUSED' | 'SUCCEEDED' | 'FAILED' | 'CANCELLED';

export interface CreateExecutionRequest {
  knowledgeBaseId: number;
  conversationId: number;
  agentMode: AgentMode;
  question: string;
}

/** AgentStepSummary.Status; ReAct uses COMPLETED as well as SUCCEEDED. */
export type ExecutionStepStatus =
  'PENDING' | 'RUNNING' | 'COMPLETED' | 'SUCCEEDED' | 'FAILED' | 'SKIPPED';

export interface ExecutionStep {
  stepId: string;
  kind: string;
  operation: string;
  status: string;
  dependsOn: unknown;
  errorCode: string | null;
}

export interface ExecutionToolCall {
  invocationKey: string;
  toolName: string;
  status: string;
  argumentsSummary: unknown;
  elapsedMillis: number | null;
  errorCode: string | null;
}

/** Actual ExecutionResponse DTO. JsonNode fields stay unknown until explicitly projected. */
export interface ExecutionResponse {
  executionId: string;
  knowledgeBaseId: number;
  conversationId: number | null;
  agentMode: AgentMode;
  status: ExecutionStatus;
  currentStep: string | null;
  version: number;
  deadlineAt: string;
  cancelRequested: boolean;
  retryable: boolean;
  result: unknown;
  errorCode: string | null;
  steps: ExecutionStep[];
  toolCalls: ExecutionToolCall[];
  createdAt: string;
  updatedAt: string;
}

/** Execution list summary, matching the backend paginated projection. */
export interface ExecutionSummary {
  executionId: string;
  conversationId: number | null;
  knowledgeBaseId: number;
  agentMode: AgentMode;
  status: ExecutionStatus;
  currentStep: string | null;
  startedAt: string | null;
  updatedAt: string;
}

export interface ExecutionListParams extends PaginationParams {
  status?: ExecutionStatus;
  agentMode?: AgentMode;
}

/** Planned chunk DTO, exactly the fields documented in §8.3. */
export interface DocumentChunk {
  chunkId: number;
  chunkIndex: number;
  content: string;
  pageFrom: number | null;
  pageTo: number | null;
  sectionTitle: string | null;
}

export interface DocumentChunkPage {
  content: DocumentChunk[];
  page: number;
  size: number;
  totalElements: number;
}

/** Public health projection from §8.7. Never use the full Actuator response. */
export type HealthStatus = 'UP' | 'DOWN' | 'UNKNOWN';
export interface SystemHealth {
  status: HealthStatus;
  components: Partial<
    Record<'database' | 'redis' | 'rabbitmq' | 'qdrant' | 'elasticsearch', HealthStatus>
  >;
}
