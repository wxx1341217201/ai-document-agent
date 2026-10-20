import { SettingOutlined, MessageOutlined, UploadOutlined } from '@ant-design/icons';
import { Button } from 'antd';
import { useEffect, useState } from 'react';
import { useNavigate, useParams } from 'react-router-dom';
import { ErrorResult, PageError, PageLoading } from '../../components/feedback';
import { PageHeader } from '../../components/layout/PageHeader';
import { useUiStore } from '../../stores/ui.store';
import { parsePositiveRouteId } from '../../utils/route-params';
import { DocumentTable } from '../document/DocumentTable';
import { DocumentUploadDragger } from '../document/DocumentUploadDragger';
import { useDocumentList } from '../document/document.hooks';
import { KnowledgeBaseFormModal } from './KnowledgeBaseFormModal';
import { useKnowledgeBase, useUpdateKnowledgeBase } from './knowledge-base.hooks';

export function KnowledgeBaseDetailPage() {
  const { kbId: rawKnowledgeBaseId } = useParams();
  const knowledgeBaseId = parsePositiveRouteId(rawKnowledgeBaseId);
  const knowledgeBaseQuery = useKnowledgeBase(knowledgeBaseId);
  const updateMutation = useUpdateKnowledgeBase();
  const navigate = useNavigate();
  const setCurrentKnowledgeBase = useUiStore((state) => state.setCurrentKnowledgeBase);
  const [uploadOpen, setUploadOpen] = useState(false);
  const documents = useDocumentList(knowledgeBaseId, { page: 0, size: 20 });
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
    setUploadOpen(true);
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
            <Button
              type="primary"
              onClick={() => navigate(`/knowledge-bases/${knowledgeBase.id}/reading`)}
            >
              主题阅读
            </Button>
            <Button
              icon={<MessageOutlined />}
              onClick={() => navigate(`/knowledge-bases/${knowledgeBase.id}/chat`)}
            >
              开始问答
            </Button>
            <Button icon={<UploadOutlined />} onClick={focusUpload}>
              上传文档
            </Button>
            <Button
              aria-label="知识库设置"
              title="知识库设置"
              icon={<SettingOutlined />}
              onClick={() => setEditOpen(true)}
            />
          </>
        }
      />

      <div id="document-upload">
        {uploadOpen || (documents.isSuccess && documents.data.content.length === 0) ? (
          <DocumentUploadDragger knowledgeBaseId={knowledgeBase.id} />
        ) : null}
      </div>
      <DocumentTable knowledgeBaseId={knowledgeBase.id} />

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
