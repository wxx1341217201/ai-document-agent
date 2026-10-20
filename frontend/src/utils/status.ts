import type { DocumentStatus, ExecutionStatus, HealthStatus } from '../api/contracts';

type KnownStatus = DocumentStatus | ExecutionStatus | HealthStatus;
const statuses: Record<KnownStatus, { color: string; label: string }> = {
  UPLOADED: { color: 'blue', label: '上传完成' },
  QUEUED: { color: 'gold', label: '排队中' },
  PROCESSING: { color: 'processing', label: '处理中' },
  RETRYING: { color: 'processing', label: '重试中' },
  READY: { color: 'success', label: '已就绪' },
  FAILED: { color: 'error', label: '失败' },
  DELETED: { color: 'default', label: '已删除' },
  PENDING: { color: 'gold', label: '等待中' },
  RUNNING: { color: 'processing', label: '执行中' },
  PAUSED: { color: 'warning', label: '已暂停' },
  SUCCEEDED: { color: 'success', label: '已完成' },
  CANCELLED: { color: 'default', label: '已取消' },
  UP: { color: 'success', label: '正常' },
  DOWN: { color: 'error', label: '不可用' },
  UNKNOWN: { color: 'default', label: '未知' },
};

export function statusMetadata(status: string) {
  return Object.hasOwn(statuses, status)
    ? statuses[status as KnownStatus]
    : { color: 'default', label: '未知状态' };
}
