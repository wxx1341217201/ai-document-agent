/** M18's actual JSON protocol. A document executionId identifies its ingestion job. */
export type ProgressSubscription =
  | { resource: 'EXECUTION'; knowledgeBaseId: number; executionId: string }
  | { resource: 'DOCUMENT'; knowledgeBaseId: number; documentId: number };

export const progressEventTypes = [
  'DOCUMENT_QUEUED',
  'PARSING',
  'CHUNKING',
  'INDEXING',
  'DOCUMENT_READY',
  'DOCUMENT_FAILED',
  'EXECUTION_STARTED',
  'PLAN_CREATED',
  'STEP_STARTED',
  'TOOL_COMPLETED',
  'STEP_COMPLETED',
  'EXECUTION_COMPLETED',
  'EXECUTION_FAILED',
] as const;

export interface ProgressEvent {
  eventId: string;
  executionId: string;
  sequence: number;
  type: (typeof progressEventTypes)[number];
  timestamp: string;
  payload: {
    stepId: string | null;
    status: string;
    summary: string | null;
    toolName: string | null;
    documentId: number | null;
    errorCode: string | null;
  };
}

export type ProgressConnectionState =
  'idle' | 'connecting' | 'subscribing' | 'subscribed' | 'reconnecting' | 'unavailable';

export interface ProgressSnapshot {
  connection: ProgressConnectionState;
  event: ProgressEvent | null;
  sequence: number;
  gap: { expected: number; received: number } | null;
  rejectionCode: string | null;
}

export type ProgressResyncReason = 'subscribe' | 'reconnect' | 'gap' | 'terminal';

export interface ProgressObserver {
  onChange: (snapshot: ProgressSnapshot) => void;
  onResync: (reason: ProgressResyncReason, snapshot: ProgressSnapshot) => void;
}

export const idleProgress: ProgressSnapshot = {
  connection: 'idle',
  event: null,
  sequence: 0,
  gap: null,
  rejectionCode: null,
};

export function progressSubscriptionKey(subscription: ProgressSubscription): string {
  const id =
    subscription.resource === 'EXECUTION' ? subscription.executionId : subscription.documentId;
  return `${subscription.resource}:${subscription.knowledgeBaseId}:${id}`;
}

export function isTerminalProgressEvent(event: ProgressEvent): boolean {
  return (
    event.type === 'DOCUMENT_READY' ||
    event.type === 'DOCUMENT_FAILED' ||
    event.type === 'EXECUTION_COMPLETED' ||
    event.type === 'EXECUTION_FAILED'
  );
}

export function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value);
}

export function isPositiveId(value: unknown): value is number {
  return typeof value === 'number' && Number.isSafeInteger(value) && value > 0;
}

export function isUuid(value: unknown): value is string {
  return (
    typeof value === 'string' &&
    /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(value)
  );
}

const safeSummaries = new Set([
  '文档已排队',
  '正在解析文档',
  '正在切分文档',
  '正在建立索引',
  '文档已就绪',
  '文档处理失败',
  '执行已开始',
  '计划已创建',
  '步骤状态已更新',
  '工具调用已结束',
  '执行已完成',
  '执行已结束',
]);

/** Project only ProgressPayload's safe fields; never retain arbitrary model/tool output. */
export function parseProgressEvent(value: Record<string, unknown>): ProgressEvent | undefined {
  if (
    !isUuid(value.eventId) ||
    !isUuid(value.executionId) ||
    !isPositiveId(value.sequence) ||
    !progressEventTypes.includes(value.type as ProgressEvent['type']) ||
    typeof value.timestamp !== 'string' ||
    !Number.isFinite(Date.parse(value.timestamp)) ||
    !isRecord(value.payload)
  ) {
    return undefined;
  }
  const payload = value.payload;
  const nullable = (field: unknown, pattern: RegExp) =>
    field == null || (typeof field === 'string' && pattern.test(field));
  if (
    typeof payload.status !== 'string' ||
    !/^[A-Z][A-Z0-9_]{0,31}$/.test(payload.status) ||
    !nullable(payload.stepId, /^[A-Za-z][A-Za-z0-9_.-]{0,63}$/) ||
    !nullable(payload.toolName, /^[A-Za-z][A-Za-z0-9_.-]{0,63}$/) ||
    !nullable(payload.errorCode, /^[A-Z][A-Z0-9_]{0,63}$/) ||
    (payload.documentId != null && !isPositiveId(payload.documentId)) ||
    (payload.summary != null &&
      (typeof payload.summary !== 'string' || !safeSummaries.has(payload.summary)))
  ) {
    return undefined;
  }
  return {
    eventId: value.eventId,
    executionId: value.executionId,
    sequence: value.sequence,
    type: value.type as ProgressEvent['type'],
    timestamp: value.timestamp,
    payload: {
      status: payload.status,
      stepId: (payload.stepId as string | null | undefined) ?? null,
      toolName: (payload.toolName as string | null | undefined) ?? null,
      summary: (payload.summary as string | null | undefined) ?? null,
      errorCode: (payload.errorCode as string | null | undefined) ?? null,
      documentId: (payload.documentId as number | null | undefined) ?? null,
    },
  };
}
