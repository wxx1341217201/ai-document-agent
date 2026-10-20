import { DeleteOutlined, MoreOutlined, ReloadOutlined } from '@ant-design/icons';
import { App, Button, Dropdown, Progress, Table, Typography } from 'antd';
import type { TableColumnsType } from 'antd';
import { useState } from 'react';
import { Link } from 'react-router-dom';
import type { DocumentRecord } from '../../api/contracts';
import {
  ConfirmDeleteModal,
  EmptyState,
  PageError,
  PageLoading,
  StatusTag,
  RequestErrorAlert,
} from '../../components/feedback';
import { formatBytes, formatDateTime } from '../../utils/format';
import { isDocumentProcessing } from '../../utils/document-status';
import { useDeleteDocument, useDocumentList, useRetryDocument } from './document.hooks';
import { processingStage } from './document-utils';
import { useDocumentProgress } from '../progress/useDocumentProgress';

const DEFAULT_PAGE_SIZE = 20;

export function DocumentTable({ knowledgeBaseId }: { knowledgeBaseId: number }) {
  const { message } = App.useApp();
  const [page, setPage] = useState(0);
  const size = DEFAULT_PAGE_SIZE;
  const [deleteTarget, setDeleteTarget] = useState<DocumentRecord>();
  const documentQuery = useDocumentList(knowledgeBaseId, { page, size });
  const retryMutation = useRetryDocument(knowledgeBaseId);
  const deleteMutation = useDeleteDocument(knowledgeBaseId);

  const columns: TableColumnsType<DocumentRecord> = [
    {
      title: '文档',
      dataIndex: 'originalName',
      render: (originalName: string, record) => (
        <div className="document-file">
          <Link to={`/knowledge-bases/${knowledgeBaseId}/documents/${record.id}`}>
            {originalName}
          </Link>
          <Typography.Text type="secondary">
            {record.extension.toUpperCase()} · {formatBytes(record.sizeBytes)}
          </Typography.Text>
        </div>
      ),
    },
    {
      title: '状态',
      width: 220,
      render: (_, record) => (
        <div>
          <StatusTag status={record.status} />
          {isDocumentProcessing(record.status) ? (
            <DocumentProcessingProgress document={record} />
          ) : null}
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
        </div>
      ),
    },
    { title: '更新时间', dataIndex: 'updatedAt', width: 170, render: formatDateTime },
    {
      title: '操作',
      width: 72,
      render: (_, record) => (
        <Dropdown
          trigger={['click']}
          menu={{
            items: [
              {
                key: 'delete',
                label: isDocumentProcessing(record.status) ? '处理中，暂不能删除' : '删除文档',
                danger: true,
                disabled: isDocumentProcessing(record.status),
                icon: <DeleteOutlined />,
                onClick: () => openDelete(record),
              },
            ],
          }}
        >
          <Button
            type="text"
            icon={<MoreOutlined />}
            aria-label={`更多操作 ${record.originalName}`}
          />
        </Dropdown>
      ),
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
      {retryMutation.error ? <RequestErrorAlert error={retryMutation.error} /> : null}
      {documents.length === 0 ? (
        <EmptyState title="还没有文档" description="上传第一个文档后，处理状态会在此处更新。" />
      ) : (
        <Table
          rowKey="id"
          columns={columns}
          dataSource={documents}
          scroll={{ x: 650 }}
          pagination={{
            current: page + 1,
            pageSize: size,
            total: documentQuery.data?.totalElements,
            showSizeChanger: false,
            hideOnSinglePage: true,
            showTotal: (total) => `共 ${total} 个文档`,
          }}
          onChange={(pagination) => setPage((pagination.current ?? 1) - 1)}
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

function DocumentProcessingProgress({ document }: { document: DocumentRecord }) {
  const { status } = document;
  const { progress } = useDocumentProgress(document.knowledgeBaseId, document.id, {
    initialData: document,
    enabled: isDocumentProcessing(status),
  });
  const stage = processingStage(status);
  const progressStatus =
    status === 'FAILED' ? 'exception' : status === 'READY' ? 'success' : 'active';
  return (
    <div className="document-processing" aria-label={`流程状态：${stage.label}`}>
      <Progress percent={stage.percent} size="small" status={progressStatus} showInfo={false} />
      <Typography.Text type="secondary">
        {progress.event?.payload.summary ?? stage.label}
      </Typography.Text>
    </div>
  );
}
