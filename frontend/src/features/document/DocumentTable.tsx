import { DeleteOutlined, EyeOutlined, ReloadOutlined } from '@ant-design/icons';
import { Button, Progress, Table, Tooltip, Typography, message } from 'antd';
import type { TableColumnsType } from 'antd';
import { useState } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import type { DocumentRecord } from '../../api/contracts';
import {
  ConfirmDeleteModal,
  EmptyState,
  PageError,
  PageLoading,
  StatusTag,
} from '../../components/feedback';
import { formatBytes, formatDateTime } from '../../utils/format';
import { isDocumentProcessing } from '../../utils/document-status';
import { useDeleteDocument, useDocumentList, useRetryDocument } from './document.hooks';
import { processingStage } from './document-utils';

const DEFAULT_PAGE_SIZE = 20;

export function DocumentTable({ knowledgeBaseId }: { knowledgeBaseId: number }) {
  const navigate = useNavigate();
  const [page, setPage] = useState(0);
  const [size, setSize] = useState(DEFAULT_PAGE_SIZE);
  const [deleteTarget, setDeleteTarget] = useState<DocumentRecord>();
  const documentQuery = useDocumentList(knowledgeBaseId, { page, size });
  const retryMutation = useRetryDocument(knowledgeBaseId);
  const deleteMutation = useDeleteDocument(knowledgeBaseId);

  const columns: TableColumnsType<DocumentRecord> = [
    {
      title: '文件名',
      dataIndex: 'originalName',
      render: (originalName: string, record) => (
        <Link to={`/knowledge-bases/${knowledgeBaseId}/documents/${record.id}`}>
          {originalName}
        </Link>
      ),
    },
    {
      title: '类型',
      dataIndex: 'extension',
      width: 90,
      render: (extension: string) => extension.toUpperCase(),
    },
    {
      title: '大小',
      dataIndex: 'sizeBytes',
      width: 100,
      render: formatBytes,
    },
    {
      title: '状态',
      dataIndex: 'status',
      width: 120,
      render: (status: DocumentRecord['status']) => <StatusTag status={status} />,
    },
    {
      title: '处理进度',
      dataIndex: 'status',
      width: 210,
      render: (status: DocumentRecord['status']) => <DocumentProcessingProgress status={status} />,
    },
    {
      title: '更新时间',
      dataIndex: 'updatedAt',
      width: 170,
      render: formatDateTime,
    },
    {
      title: '操作',
      key: 'actions',
      width: 175,
      render: (_, record) => {
        const processing = isDocumentProcessing(record.status);
        return (
          <span className="table-actions">
            <Tooltip title="查看文档详情">
              <Button
                type="link"
                size="small"
                icon={<EyeOutlined />}
                aria-label={`查看 ${record.originalName}`}
                onClick={() =>
                  navigate(`/knowledge-bases/${knowledgeBaseId}/documents/${record.id}`)
                }
              >
                查看
              </Button>
            </Tooltip>
            {record.status === 'FAILED' ? (
              <Button
                type="link"
                size="small"
                icon={<ReloadOutlined />}
                loading={retryMutation.isPending && retryMutation.variables === record.id}
                onClick={() => retryDocument(record)}
              >
                重试
              </Button>
            ) : null}
            <Tooltip title={processing ? '处理中时不能删除，请等待任务完成。' : '删除文档'}>
              <span>
                <Button
                  type="link"
                  danger
                  size="small"
                  icon={<DeleteOutlined />}
                  disabled={processing}
                  onClick={() => openDelete(record)}
                >
                  删除
                </Button>
              </span>
            </Tooltip>
          </span>
        );
      },
    },
  ];

  function retryDocument(document: DocumentRecord) {
    retryMutation.mutate(document.id, {
      onSuccess: () => message.success(`“${document.originalName}”已提交重新处理任务`),
    });
  }

  function openDelete(document: DocumentRecord) {
    deleteMutation.reset();
    setDeleteTarget(document);
  }

  function confirmDelete() {
    if (!deleteTarget) {
      return;
    }
    deleteMutation.mutate(deleteTarget.id, {
      onSuccess: () => {
        message.success('文档已删除');
        setDeleteTarget(undefined);
      },
    });
  }

  if (documentQuery.isLoading) {
    return <PageLoading label="正在加载文档…" />;
  }

  if (documentQuery.isError) {
    return (
      <PageError
        error={documentQuery.error}
        onRetry={() => void documentQuery.refetch()}
        title="文档列表暂时无法加载"
      />
    );
  }

  const documents = documentQuery.data?.content ?? [];
  return (
    <>
      {documents.length === 0 ? (
        <EmptyState title="还没有文档" description="上传第一个文档后，处理状态会在此处更新。" />
      ) : (
        <Table
          rowKey="id"
          columns={columns}
          dataSource={documents}
          scroll={{ x: 980 }}
          pagination={{
            current: page + 1,
            pageSize: size,
            total: documentQuery.data?.totalElements,
            showSizeChanger: true,
            showTotal: (total) => `共 ${total} 个文档`,
          }}
          onChange={(pagination) => {
            const nextSize = pagination.pageSize ?? size;
            setSize(nextSize);
            setPage(nextSize === size ? (pagination.current ?? 1) - 1 : 0);
          }}
        />
      )}
      <ConfirmDeleteModal
        open={Boolean(deleteTarget)}
        title="删除文档"
        description={
          deleteTarget
            ? `确定删除“${deleteTarget.originalName}”吗？原始文件将一并删除。`
            : '确定删除此文档吗？'
        }
        loading={deleteMutation.isPending}
        error={deleteMutation.error}
        onCancel={() => setDeleteTarget(undefined)}
        onConfirm={confirmDelete}
      />
    </>
  );
}

function DocumentProcessingProgress({ status }: { status: DocumentRecord['status'] }) {
  const stage = processingStage(status);
  const progressStatus =
    status === 'FAILED' ? 'exception' : status === 'READY' ? 'success' : 'active';
  return (
    <div className="document-processing" aria-label={`流程状态：${stage.label}`}>
      <Progress percent={stage.percent} size="small" status={progressStatus} showInfo={false} />
      <Typography.Text type="secondary">{stage.label}</Typography.Text>
    </div>
  );
}
