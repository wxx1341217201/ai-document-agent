import { FileTextOutlined, RightOutlined } from '@ant-design/icons';
import { Button, Collapse, Descriptions, Drawer, Typography } from 'antd';
import { useNavigate } from 'react-router-dom';
import type { RagCitation } from '../../api/contracts';
import { citationContextMockEnabled } from '../../api/document-preview.api';
import { EmptyState, PageLoading, RequestErrorAlert, RetryButton } from '../../components/feedback';
import { useCitationContext } from '../document/preview.hooks';
import { formatCitationPages } from '../../utils/format';

interface CitationDrawerProps {
  knowledgeBaseId: number;
  citation?: RagCitation;
  onClose: () => void;
}

/** The original quote stays visible even when optional context cannot be loaded. */
export function CitationDrawer({ knowledgeBaseId, citation, onClose }: CitationDrawerProps) {
  const navigate = useNavigate();
  const canViewDocument = Boolean(
    citation && Number.isSafeInteger(citation.documentId) && citation.documentId > 0,
  );
  return (
    <Drawer
      title={citation ? '[' + citation.citationId + '] 引用详情' : '引用详情'}
      open={Boolean(citation)}
      onClose={onClose}
      width="min(480px, 100vw)"
      destroyOnHidden
    >
      {citation ? (
        <>
          <Descriptions bordered size="small" column={1}>
            <Descriptions.Item label="文档">{citation.documentName}</Descriptions.Item>
            <Descriptions.Item label="页码">
              {formatCitationPages(citation.pageFrom, citation.pageTo)}
            </Descriptions.Item>
          </Descriptions>
          <Typography.Title level={5}>本次回答的原文摘录</Typography.Title>
          {citation.quote.trim() ? (
            <Typography.Paragraph className="citation-drawer-quote">
              {citation.quote}
            </Typography.Paragraph>
          ) : (
            <Typography.Text type="secondary">暂无原文摘录。</Typography.Text>
          )}
          <Button
            aria-label="查看文档"
            type="primary"
            icon={<FileTextOutlined />}
            disabled={!canViewDocument}
            onClick={() => {
              if (!canViewDocument) return;
              const params = new URLSearchParams();
              if (Number.isSafeInteger(citation.pageFrom) && Number(citation.pageFrom) > 0)
                params.set('page', String(citation.pageFrom));
              if (Number.isSafeInteger(citation.chunkId) && citation.chunkId > 0)
                params.set('chunkId', String(citation.chunkId));
              navigate(
                '/knowledge-bases/' +
                  knowledgeBaseId +
                  '/documents/' +
                  citation.documentId +
                  (params.size ? '?' + params.toString() : ''),
              );
              onClose();
            }}
          >
            查看文档
          </Button>
          <Collapse
            expandIcon={({ isActive }) => (
              <RightOutlined rotate={isActive ? 90 : 0} aria-hidden="true" />
            )}
            key={citation.citationId + '-' + citation.chunkId}
            className="citation-extra"
            ghost
            items={[
              ...(citationContextMockEnabled
                ? [
                    {
                      key: 'context',
                      label: '更多上下文',
                      children: (
                        <CitationContext knowledgeBaseId={knowledgeBaseId} citation={citation} />
                      ),
                    },
                  ]
                : []),
              {
                key: 'diagnostics',
                label: '技术信息',
                children: (
                  <Descriptions size="small" column={1}>
                    <Descriptions.Item label="Document ID">{citation.documentId}</Descriptions.Item>
                    <Descriptions.Item label="Chunk ID">{citation.chunkId}</Descriptions.Item>
                  </Descriptions>
                ),
              },
            ]}
          />
        </>
      ) : null}
    </Drawer>
  );
}

function CitationContext({
  knowledgeBaseId,
  citation,
}: {
  knowledgeBaseId: number;
  citation: RagCitation;
}) {
  const context = useCitationContext(knowledgeBaseId, citation);
  return (
    <section aria-label="Citation 相邻上下文">
      {context.isLoading ? (
        <PageLoading label="正在加载引用上下文…" />
      ) : context.isError ? (
        <>
          <RequestErrorAlert error={context.error} />
          <RetryButton onClick={() => void context.refetch()} />
        </>
      ) : context.data?.length ? (
        context.data.map((chunk) => (
          <div
            key={chunk.chunkId}
            className={
              chunk.chunkId === citation.chunkId ? 'document-chunk cited-chunk' : 'document-chunk'
            }
          >
            <Typography.Text strong>
              {chunk.chunkId === citation.chunkId ? '引用片段' : '相邻片段'}
            </Typography.Text>
            <Typography.Paragraph type="secondary">
              {formatCitationPages(chunk.pageFrom, chunk.pageTo)}
            </Typography.Paragraph>
            <Typography.Paragraph className="document-extracted-text">
              {chunk.content}
            </Typography.Paragraph>
          </div>
        ))
      ) : (
        <EmptyState description="服务端未返回相邻片段。" />
      )}
    </section>
  );
}
