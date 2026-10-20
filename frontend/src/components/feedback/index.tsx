import {
  CheckCircleOutlined,
  ClockCircleOutlined,
  CloseCircleOutlined,
  CopyOutlined,
  ExclamationCircleOutlined,
  LoadingOutlined,
  PauseCircleOutlined,
  ReloadOutlined,
} from '@ant-design/icons';
import { Alert, Button, Empty, Modal, Result, Spin, Tag, Typography, message } from 'antd';
import type { ReactNode } from 'react';
import { normalizeApiError } from '../../api/client';
import type { DocumentStatus } from '../../api/contracts';
import { statusMetadata } from '../../utils/status';

export function PageLoading({ label = '正在加载…' }: { label?: string }) {
  return (
    <div className="page-state" role="status" aria-live="polite">
      <Spin size="large" />
      <Typography.Text>{label}</Typography.Text>
    </div>
  );
}

export function PageError({
  error,
  onRetry,
  title = '页面暂时无法加载',
}: {
  error: unknown;
  onRetry?: () => void;
  title?: string;
}) {
  const apiError = normalizeApiError(error);
  return (
    <Result
      status={apiError.httpStatus === 404 ? '404' : 'error'}
      title={title}
      subTitle={apiError.message}
      extra={
        <div className="result-actions">
          {onRetry ? <RetryButton onClick={onRetry} /> : null}
          {apiError.traceId ? <TraceIdCopy traceId={apiError.traceId} /> : null}
        </div>
      }
    />
  );
}

export function EmptyState({
  title = '暂无数据',
  description,
  action,
}: {
  title?: string;
  description?: ReactNode;
  action?: ReactNode;
}) {
  return <Empty description={<span>{description || title}</span>}>{action}</Empty>;
}

export function ErrorResult({
  status = 'error',
  title,
  subTitle,
  action,
}: {
  status?: '403' | '404' | '500' | 'error' | 'info' | 'success' | 'warning';
  title: ReactNode;
  subTitle?: ReactNode;
  action?: ReactNode;
}) {
  return <Result status={status} title={title} subTitle={subTitle} extra={action} />;
}

type SupportedStatus =
  | DocumentStatus
  | 'PENDING'
  | 'RUNNING'
  | 'PAUSED'
  | 'SUCCEEDED'
  | 'CANCELLED'
  | 'UP'
  | 'DOWN'
  | 'UNKNOWN';

const statusIcons: Record<SupportedStatus, ReactNode> = {
  UPLOADED: <ClockCircleOutlined />,
  QUEUED: <ClockCircleOutlined />,
  PROCESSING: <LoadingOutlined />,
  RETRYING: <LoadingOutlined />,
  READY: <CheckCircleOutlined />,
  FAILED: <CloseCircleOutlined />,
  DELETED: <CloseCircleOutlined />,
  PENDING: <ClockCircleOutlined />,
  RUNNING: <LoadingOutlined />,
  PAUSED: <PauseCircleOutlined />,
  SUCCEEDED: <CheckCircleOutlined />,
  CANCELLED: <CloseCircleOutlined />,
  UP: <CheckCircleOutlined />,
  DOWN: <CloseCircleOutlined />,
  UNKNOWN: <ExclamationCircleOutlined />,
};

export function StatusTag({ status }: { status: SupportedStatus | string }) {
  const publicStatus = statusMetadata(status);
  const meta = {
    ...publicStatus,
    icon: Object.hasOwn(statusIcons, status) ? (
      statusIcons[status as SupportedStatus]
    ) : (
      <ExclamationCircleOutlined />
    ),
  };

  return (
    <Tag
      className="status-tag"
      icon={meta.icon}
      color={meta.color}
      role="status"
      aria-label={`状态：${meta.label}`}
    >
      {meta.label}
    </Tag>
  );
}

export function TraceIdCopy({ traceId }: { traceId: string }) {
  const handleCopy = async () => {
    try {
      await navigator.clipboard.writeText(traceId);
      message.success('请求 ID 已复制');
    } catch {
      message.error('无法复制请求 ID，请手动复制。');
    }
  };

  return (
    <Button icon={<CopyOutlined />} onClick={() => void handleCopy()}>
      复制请求 ID
    </Button>
  );
}

export function RetryButton({
  onClick,
  loading = false,
}: {
  onClick: () => void;
  loading?: boolean;
}) {
  return (
    <Button icon={<ReloadOutlined />} onClick={onClick} loading={loading}>
      重试
    </Button>
  );
}

export function RequestErrorAlert({ error }: { error: unknown }) {
  const apiError = normalizeApiError(error);
  const details = apiError.fieldErrors ? (
    <ul className="field-error-list">
      {Object.entries(apiError.fieldErrors).map(([field, fieldMessage]) => (
        <li key={field}>{fieldMessage}</li>
      ))}
    </ul>
  ) : null;

  return (
    <Alert
      type="error"
      showIcon
      message={apiError.message}
      description={
        <div>
          {details}
          {apiError.traceId ? <TraceIdCopy traceId={apiError.traceId} /> : null}
        </div>
      }
    />
  );
}

export function ConfirmDeleteModal({
  open,
  title = '确认删除？',
  description,
  confirmText = '删除',
  loading = false,
  error,
  onCancel,
  onConfirm,
}: {
  open: boolean;
  title?: string;
  description: ReactNode;
  confirmText?: string;
  loading?: boolean;
  error?: unknown;
  onCancel: () => void;
  onConfirm: () => void;
}) {
  return (
    <Modal
      open={open}
      title={title}
      okText={confirmText}
      okType="danger"
      cancelText="取消"
      confirmLoading={loading}
      onCancel={onCancel}
      onOk={onConfirm}
      destroyOnHidden
    >
      <Typography.Paragraph>{description}</Typography.Paragraph>
      {error ? <RequestErrorAlert error={error} /> : null}
    </Modal>
  );
}
