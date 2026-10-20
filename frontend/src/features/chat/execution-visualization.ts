import type { ExecutionResponse, ExecutionStepStatus } from '../../api/contracts';
import { isRecord } from '../../api/progress.contracts';

export const stepKindLabels = {
  AGENT: '执行任务',
  PLAN: '计划',
  RETRIEVAL: '检索',
  TOOL: '工具',
  GENERATION: '生成',
  VERIFICATION: '校验',
  REFLECTION: '反思检查',
} as const;

export const stepStatusLabels: Record<ExecutionStepStatus, { label: string; color: string }> = {
  PENDING: { label: '等待中', color: 'default' },
  RUNNING: { label: '执行中', color: 'processing' },
  COMPLETED: { label: '已完成', color: 'success' },
  SUCCEEDED: { label: '已完成', color: 'success' },
  FAILED: { label: '失败', color: 'error' },
  SKIPPED: { label: '已跳过', color: 'default' },
};

const operationLabels: Record<string, string> = {
  execute: '运行 Agent',
  validated_plan: '计划已校验',
  SEARCH: '搜索知识库',
  FETCH_CONTEXT: '读取片段上下文',
  SYNTHESIZE: '综合证据生成回答',
  hybrid_retrieve: '混合检索',
  cited_generate: '生成带引用的回答',
  cited_answer: '生成带引用的回答',
  safe_stop: '安全终止检查',
  coverage_complete: '证据覆盖检查通过',
  coverage_gap: '证据覆盖存在缺口',
  searchKnowledgeBase: '搜索知识库',
  getChunkContext: '读取片段上下文',
  getDocumentMetadata: '读取文档元数据',
};

// ExecutionStore.safeTool only persists these numeric fields. Never show free-form arguments.
const argumentLabels = {
  queryLength: '检索词长度',
  topK: 'topK',
  chunkId: '片段 ID',
  radius: '上下文范围',
  documentId: '文档 ID',
} as const;

interface SafeStep {
  stepId: string;
  kind: keyof typeof stepKindLabels;
  operation: string;
  status: ExecutionStepStatus;
  dependsOn: string[];
  errorCode: string | null;
}

interface SafeTool {
  invocationKey: string;
  toolName: string;
  status: ExecutionStepStatus;
  arguments: { label: string; value: string }[];
  elapsedMillis: number | null;
  errorCode: string | null;
}

export function safeMachineId(value: unknown): string | null {
  return typeof value === 'string' && /^[A-Za-z][A-Za-z0-9_.-]{0,63}$/.test(value) ? value : null;
}

export function safeErrorCode(value: unknown): string | null {
  return typeof value === 'string' && /^[A-Z][A-Z0-9_]{0,63}$/.test(value) ? value : null;
}

export function operationLabel(operation: string): string {
  return Object.hasOwn(operationLabels, operation) ? operationLabels[operation] : operation;
}

function isStepStatus(value: unknown): value is ExecutionStepStatus {
  return typeof value === 'string' && Object.hasOwn(stepStatusLabels, value);
}

/** Project fixed summary fields only; ignore reasoning, model text and raw tool output. */
export function executionVisualization(execution: ExecutionResponse) {
  const steps: SafeStep[] = [];
  const toolCalls: SafeTool[] = [];
  const seenSteps = new Set<string>();
  const seenTools = new Set<string>();
  for (const step of Array.isArray(execution.steps) ? execution.steps : []) {
    if (
      !isRecord(step) ||
      !safeMachineId(step.stepId) ||
      !safeMachineId(step.operation) ||
      typeof step.kind !== 'string' ||
      !Object.hasOwn(stepKindLabels, step.kind) ||
      !isStepStatus(step.status) ||
      seenSteps.has(step.stepId)
    )
      continue;
    seenSteps.add(step.stepId);
    steps.push({
      stepId: step.stepId,
      kind: step.kind as SafeStep['kind'],
      operation: step.operation,
      status: step.status,
      dependsOn: Array.isArray(step.dependsOn)
        ? [
            ...new Set(
              step.dependsOn.filter((value): value is string => safeMachineId(value) !== null),
            ),
          ]
        : [],
      errorCode: safeErrorCode(step.errorCode),
    });
  }
  for (const tool of Array.isArray(execution.toolCalls) ? execution.toolCalls : []) {
    if (
      !isRecord(tool) ||
      !safeMachineId(tool.toolName) ||
      !isStepStatus(tool.status) ||
      typeof tool.invocationKey !== 'string' ||
      !tool.invocationKey ||
      seenTools.has(tool.invocationKey)
    )
      continue;
    seenTools.add(tool.invocationKey);
    const summary = isRecord(tool.argumentsSummary) ? tool.argumentsSummary : {};
    toolCalls.push({
      invocationKey: tool.invocationKey,
      toolName: tool.toolName,
      status: tool.status,
      arguments: Object.entries(argumentLabels).flatMap(([key, label]) => {
        const value = summary[key];
        return typeof value === 'string' && /^[0-9]{1,20}$/.test(value) ? [{ label, value }] : [];
      }),
      elapsedMillis:
        typeof tool.elapsedMillis === 'number' &&
        Number.isSafeInteger(tool.elapsedMillis) &&
        tool.elapsedMillis >= 0
          ? tool.elapsedMillis
          : null,
      errorCode: safeErrorCode(tool.errorCode),
    });
  }
  return { steps, toolCalls, currentStep: safeMachineId(execution.currentStep) };
}
