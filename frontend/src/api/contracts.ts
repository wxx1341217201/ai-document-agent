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
