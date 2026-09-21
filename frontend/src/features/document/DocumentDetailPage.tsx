import {
  DeleteOutlined,
  DownloadOutlined,
  MessageOutlined,
  ReloadOutlined,
} from '@ant-design/icons';
import { Alert, Button, Card, Descriptions, Steps, Typography, message } from 'antd';
import { useEffect, useState } from 'react';
import { useNavigate, useParams } from 'react-router-dom';
import { documentApi } from '../../api/document.api';
import {
  ConfirmDeleteModal,
  ErrorResult,
  PageError,
  PageLoading,
  RequestErrorAlert,
  StatusTag,
} from '../../components/feedback';
import { PageHeader } from '../../components/layout/PageHeader';
import { useUiStore } from '../../stores/ui.store';
import { abbreviateHash, formatBytes, formatDateTime } from '../../utils/format';
import { isDocumentProcessing } from '../../utils/document-status';
import { parsePositiveRouteId } from '../../utils/route-params';
import { useKnowledgeBase } from '../knowledge-base/knowledge-base.hooks';
import { useDeleteDocument, useDocument, useRetryDocument } from './document.hooks';

export function DocumentDetailPage() {
  const { kbId: rawKnowledgeBaseId, documentId: rawDocumentId } = useParams();
  const knowledgeBaseId = parsePositiveRouteId(rawKnowledgeBaseId);
  const documentId = parsePositiveRouteId(rawDocumentId);
  const knowledgeBaseQuery = useKnowledgeBase(knowledgeBaseId);
  const documentQuery = useDocument(knowledgeBaseId, documentId);
  const retryMutation = useRetryDocument(knowledgeBaseId ?? -1);
  const deleteMutation = useDeleteDocument(knowledgeBaseId ?? -1);
  const setCurrentKnowledgeBase = useUiStore((state) => state.setCurrentKnowledgeBase);
  const navigate = useNavigate();
  const [deleteOpen, setDeleteOpen] = useState(false);

  useEffect(() => {
    if (knowledgeBaseQuery.data) {
      setCurrentKnowledgeBase({
        id: knowledgeBaseQuery.data.id,
        name: knowledgeBaseQuery.data.name,
      });
    }
    return () => setCurrentKnowledgeBase(undefined);
  }, [knowledgeBaseQuery.data, setCurrentKnowledgeBase]);

  if (!knowledgeBaseId || !documentId) {
    return (
      <ErrorResult status="404" title="文档地址无效" subTitle="请从知识库中的文档列表进入详情。" />
    );
  }

  if (documentQuery.isLoading) {
    return <PageLoading label="正在加载文档详情…" />;
  }

  if (documentQuery.isError) {
    return (
      <PageError
        error={documentQuery.error}
        onRetry={() => void documentQuery.refetch()}
        title="文档详情暂时无法加载"
      />
    );
  }

  const document = documentQuery.data;
  if (!document) {
    return <PageError error={new Error('文档数据为空')} />;
  }

  const isProcessing = isDocumentProcessing(document.status);

  function retry(currentDocumentId: number) {
    retryMutation.mutate(currentDocumentId, {
      onSuccess: () => message.success('文档已提交重新处理任务'),
    });
  }

  function deleteDocument(currentDocumentId: number) {
    deleteMutation.mutate(currentDocumentId, {
      onSuccess: () => {
        message.success('文档已删除');
        navigate(`/knowledge-bases/${knowledgeBaseId}`);
      },
    });
  }

  return (
    <>
      <PageHeader
        title={document.originalName}
        description="文档元数据和处理状态由服务端返回；不会暴露内部存储路径或处理日志。"
        actions={
          <>
            <Button onClick={() => navigate(`/knowledge-bases/${knowledgeBaseId}`)}>
              返回知识库
            </Button>
            {document.status === 'READY' ? (
              <Button
                icon={<MessageOutlined />}
                onClick={() => navigate(`/knowledge-bases/${knowledgeBaseId}/chat`)}
              >
                开始问答
              </Button>
            ) : null}
            <Button
              icon={<DownloadOutlined />}
              href={documentApi.downloadUrl(knowledgeBaseId, document.id)}
              target="_blank"
              rel="noreferrer"
              disabled={document.status === 'DELETED'}
            >
              下载原文件
            </Button>
            {document.status === 'FAILED' ? (
              <Button
                icon={<ReloadOutlined />}
                loading={retryMutation.isPending}
                onClick={() => retry(document.id)}
              >
                重新处理
              </Button>
            ) : null}
            <Button
              danger
              icon={<DeleteOutlined />}
              disabled={isProcessing}
              onClick={() => {
                deleteMutation.reset();
                setDeleteOpen(true);
              }}
            >
              删除
            </Button>
          </>
        }
      />

      {retryMutation.error ? <RequestErrorAlert error={retryMutation.error} /> : null}
      {document.status === 'FAILED' ? (
        <Alert
          className="document-detail-card"
          type="error"
          showIcon
          message={document.errorMessage || '文档处理失败'}
          description={
            <div>
              {document.errorCode ? (
                <Typography.Text>错误代码：{document.errorCode}</Typography.Text>
              ) : null}
              <Typography.Paragraph type="secondary">
                可重新处理；若问题持续出现，请复制请求 ID 后联系管理员。
              </Typography.Paragraph>
            </div>
          }
        />
      ) : null}
      {isProcessing ? (
        <Alert
          className="document-detail-card"
          type="info"
          showIcon
          message="文档正在处理"
          description="页面会每 5 秒刷新状态；接入 WebSocket 后将优先使用实时进度事件。"
        />
      ) : null}

      <Card className="document-detail-card" title="基本信息">
        <Descriptions bordered column={{ xs: 1, sm: 2 }}>
          <Descriptions.Item label="文件名">{document.originalName}</Descriptions.Item>
          <Descriptions.Item label="状态">
            <StatusTag status={document.status} />
          </Descriptions.Item>
          <Descriptions.Item label="类型">
            {document.contentType || document.extension.toUpperCase()}
          </Descriptions.Item>
          <Descriptions.Item label="大小">{formatBytes(document.sizeBytes)}</Descriptions.Item>
          <Descriptions.Item label="创建时间">
            {formatDateTime(document.createdAt)}
          </Descriptions.Item>
          <Descriptions.Item label="更新时间">
            {formatDateTime(document.updatedAt)}
          </Descriptions.Item>
          <Descriptions.Item label="SHA-256" span={2}>
            <Typography.Text className="document-sha" copyable={{ text: document.sha256 }}>
              {abbreviateHash(document.sha256)}
            </Typography.Text>
          </Descriptions.Item>
        </Descriptions>
      </Card>

      <Card title="处理流程">
        <Steps
          responsive
          current={processingStep(document.status)}
          status={document.status === 'FAILED' ? 'error' : undefined}
          items={[
            { title: '上传' },
            { title: '排队' },
            { title: '解析与切块' },
            { title: '建立索引' },
            { title: 'READY' },
          ]}
        />
      </Card>

      <ConfirmDeleteModal
        open={deleteOpen}
        title="删除文档"
        description={`确定删除“${document.originalName}”吗？原始文件将一并删除。`}
        loading={deleteMutation.isPending}
        error={deleteMutation.error}
        onCancel={() => setDeleteOpen(false)}
        onConfirm={() => deleteDocument(document.id)}
      />
    </>
  );
}

function processingStep(status: DocumentRecordStatus): number {
  switch (status) {
    case 'UPLOADED':
      return 0;
    case 'QUEUED':
      return 1;
    case 'PROCESSING':
    case 'RETRYING':
    case 'FAILED':
      return 2;
    case 'READY':
    case 'DELETED':
      return 4;
  }
}

type DocumentRecordStatus =
  'UPLOADED' | 'QUEUED' | 'PROCESSING' | 'RETRYING' | 'READY' | 'FAILED' | 'DELETED';
