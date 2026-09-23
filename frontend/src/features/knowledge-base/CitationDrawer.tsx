import { FileTextOutlined } from '@ant-design/icons';
import { Alert, Button, Descriptions, Drawer, Typography } from 'antd';
import { useNavigate } from 'react-router-dom';
import type { RagCitation } from '../../api/contracts';

interface CitationDrawerProps {
  knowledgeBaseId: number;
  citation?: RagCitation;
  onClose: () => void;
}

/** Shows the source metadata exactly as returned for the current answer. */
export function CitationDrawer({ knowledgeBaseId, citation, onClose }: CitationDrawerProps) {
  const navigate = useNavigate();
  const canViewDocument = Boolean(citation && Number.isSafeInteger(citation.documentId) && citation.documentId > 0);

  return (
    <Drawer
      title={citation ? '[' + citation.citationId + '] 引用详情' : '引用详情'}
      open={Boolean(citation)}
      onClose={onClose}
      width={480}
      destroyOnHidden
    >
      {citation ? (
        <>
          <Descriptions bordered size="small" column={1}>
            <Descriptions.Item label="文档">{citation.documentName}</Descriptions.Item>
            <Descriptions.Item label="Document ID">{citation.documentId}</Descriptions.Item>
            <Descriptions.Item label="Chunk ID">{citation.chunkId}</Descriptions.Item>
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
            <Typography.Text type="secondary">后端未返回可展示的原文摘录。</Typography.Text>
          )}

          <Alert
            className="citation-drawer-context-notice"
            type="info"
            showIcon
            message="引用上下文接口尚未提供"
            description="此处仅展示本次回答中由后端返回的 quote；页面不会推导或补全上下文。"
          />

          <Button
            type="primary"
            icon={<FileTextOutlined />}
            disabled={!canViewDocument}
            onClick={() => {
              if (!canViewDocument) {
                return;
              }
              navigate('/knowledge-bases/' + knowledgeBaseId + '/documents/' + citation.documentId);
              onClose();
            }}
          >
            查看文档
          </Button>
        </>
      ) : null}
    </Drawer>
  );
}

function formatCitationPages(pageFrom: number | null, pageTo: number | null): string {
  if (pageFrom === null && pageTo === null) {
    return '后端未返回页码';
  }
  if (pageFrom !== null && pageTo !== null && pageFrom === pageTo) {
    return '第 ' + pageFrom + ' 页';
  }
  return '第 ' + (pageFrom ?? '—') + ' 至 ' + (pageTo ?? '—') + ' 页';
}
