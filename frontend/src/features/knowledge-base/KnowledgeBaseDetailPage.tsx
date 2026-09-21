import { EditOutlined, MessageOutlined, UploadOutlined } from '@ant-design/icons';
import { Button, Descriptions, Tabs } from 'antd';
import { useEffect, useState } from 'react';
import { useNavigate, useParams } from 'react-router-dom';
import { EmptyState, ErrorResult, PageError, PageLoading } from '../../components/feedback';
import { PageHeader } from '../../components/layout/PageHeader';
import { useUiStore } from '../../stores/ui.store';
import { formatDateTime } from '../../utils/format';
import { parsePositiveRouteId } from '../../utils/route-params';
import { DocumentTable } from '../document/DocumentTable';
import { DocumentUploadDragger } from '../document/DocumentUploadDragger';
import { KnowledgeBaseFormModal } from './KnowledgeBaseFormModal';
import { useKnowledgeBase, useUpdateKnowledgeBase } from './knowledge-base.hooks';

export function KnowledgeBaseDetailPage() {
  const { kbId: rawKnowledgeBaseId } = useParams();
  const knowledgeBaseId = parsePositiveRouteId(rawKnowledgeBaseId);
  const knowledgeBaseQuery = useKnowledgeBase(knowledgeBaseId);
  const updateMutation = useUpdateKnowledgeBase();
  const navigate = useNavigate();
  const setCurrentKnowledgeBase = useUiStore((state) => state.setCurrentKnowledgeBase);
  const [activeTab, setActiveTab] = useState('documents');
  const [editOpen, setEditOpen] = useState(false);

  useEffect(() => {
    if (knowledgeBaseQuery.data) {
      setCurrentKnowledgeBase({
        id: knowledgeBaseQuery.data.id,
        name: knowledgeBaseQuery.data.name,
      });
    }
    return () => setCurrentKnowledgeBase(undefined);
  }, [knowledgeBaseQuery.data, setCurrentKnowledgeBase]);

  if (!knowledgeBaseId) {
    return (
      <ErrorResult
        status="404"
        title="知识库地址无效"
        subTitle="请从知识库列表中选择一个有效资源。"
      />
    );
  }

  if (knowledgeBaseQuery.isLoading) {
    return <PageLoading label="正在加载知识库…" />;
  }

  if (knowledgeBaseQuery.isError) {
    return (
      <PageError
        error={knowledgeBaseQuery.error}
        onRetry={() => void knowledgeBaseQuery.refetch()}
      />
    );
  }

  const knowledgeBase = knowledgeBaseQuery.data;
  if (!knowledgeBase) {
    return <PageError error={new Error('知识库数据为空')} />;
  }

  function focusUpload() {
    setActiveTab('documents');
    requestAnimationFrame(() =>
      document.getElementById('document-upload')?.scrollIntoView({ behavior: 'smooth' }),
    );
  }

  return (
    <>
      <PageHeader
        title={knowledgeBase.name}
        description={knowledgeBase.description || '暂未填写知识库描述。'}
        actions={
          <>
            <Button icon={<EditOutlined />} onClick={() => setEditOpen(true)}>
              编辑
            </Button>
            <Button
              icon={<MessageOutlined />}
              onClick={() => navigate(`/knowledge-bases/${knowledgeBase.id}/chat`)}
            >
              开始问答
            </Button>
            <Button type="primary" icon={<UploadOutlined />} onClick={focusUpload}>
              上传文档
            </Button>
          </>
        }
      />

      <Tabs
        activeKey={activeTab}
        onChange={setActiveTab}
        items={[
          {
            key: 'documents',
            label: '文档',
            children: (
              <>
                <div id="document-upload">
                  <DocumentUploadDragger knowledgeBaseId={knowledgeBase.id} />
                </div>
                <DocumentTable knowledgeBaseId={knowledgeBase.id} />
              </>
            ),
          },
          {
            key: 'quick-query',
            label: '快速问答',
            children: (
              <EmptyState
                title="快速问答将在 FE05 接入"
                description="届时会使用后端返回的答案、引用和降级提示，不在浏览器中生成引用。"
              />
            ),
          },
          {
            key: 'info',
            label: '信息',
            children: (
              <Descriptions bordered column={{ xs: 1, sm: 2 }}>
                <Descriptions.Item label="名称">{knowledgeBase.name}</Descriptions.Item>
                <Descriptions.Item label="描述">
                  {knowledgeBase.description || '—'}
                </Descriptions.Item>
                <Descriptions.Item label="创建时间">
                  {formatDateTime(knowledgeBase.createdAt)}
                </Descriptions.Item>
                <Descriptions.Item label="更新时间">
                  {formatDateTime(knowledgeBase.updatedAt)}
                </Descriptions.Item>
              </Descriptions>
            ),
          },
        ]}
      />

      <KnowledgeBaseFormModal
        open={editOpen}
        mode="edit"
        initialValues={{
          name: knowledgeBase.name,
          description: knowledgeBase.description ?? undefined,
        }}
        loading={updateMutation.isPending}
        error={updateMutation.error}
        onCancel={() => setEditOpen(false)}
        onSubmit={(values) => {
          updateMutation.mutate(
            { knowledgeBaseId: knowledgeBase.id, input: values },
            {
              onSuccess: () => {
                setEditOpen(false);
              },
            },
          );
        }}
      />
    </>
  );
}
