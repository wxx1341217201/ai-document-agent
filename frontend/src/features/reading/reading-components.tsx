import { Alert, Button, Drawer, Table, Tag, Typography } from 'antd';
import { useQuery } from '@tanstack/react-query';
import { useState } from 'react';
import { Link } from 'react-router-dom';
import { documentApi } from '../../api/document.api';
import { normalizeApiError } from '../../api/client';
import type { DocumentRecord } from '../../api/contracts';
import type { ReadingCompletion, ReadingSource, ReadingStatus } from '../../api/reading.api';
import { RagAnswerMarkdown } from '../knowledge-base/RagAnswerMarkdown';
import { completionLabels } from './reading-labels';

const statusLabels: Record<ReadingStatus, string> = {
  PENDING: '排队中',
  RUNNING: '分析中',
  INTERRUPTED: '中断待恢复',
  SUCCEEDED: '本轮已结束',
  CANCELLED: '已取消',
};
export function CompletionTag({ value }: { value: ReadingCompletion }) {
  return <Tag color={value === 'FULL' ? 'success' : undefined}>{completionLabels[value]}</Tag>;
}
export function ReadingStatusTag({ value }: { value: ReadingStatus }) {
  return <Tag>{statusLabels[value]}</Tag>;
}
export function ReadingError({ error }: { error: unknown }) {
  return error ? <Alert type="error" showIcon message={normalizeApiError(error).message} /> : null;
}

export function ReadingSourcePicker({
  knowledgeBaseId,
  selected,
  onChange,
  locked = [],
}: {
  knowledgeBaseId: number;
  selected: number[];
  onChange: (ids: number[]) => void;
  locked?: number[];
}) {
  const [page, setPage] = useState(0);
  const docs = useQuery({
    queryKey: ['reading-source-options', knowledgeBaseId, page],
    queryFn: () => documentApi.list(knowledgeBaseId, { page, size: 10 }),
  });
  return (
    <div className="reading-source-picker">
      <div className="reading-section-caption">
        <span>已选 {selected.length} / 20 份资料</span>
        <Button type="text" size="small" onClick={() => void docs.refetch()}>
          刷新资料
        </Button>
      </div>
      <ReadingError error={docs.error} />
      <Table<DocumentRecord>
        rowKey="id"
        size="small"
        loading={docs.isLoading}
        dataSource={docs.data?.content ?? []}
        rowSelection={{
          selectedRowKeys: selected,
          preserveSelectedRowKeys: true,
          onChange: (keys) => {
            if (keys.length <= 20) onChange(keys.map(Number));
          },
          getCheckboxProps: (record) => ({
            'aria-label': `选择资料 ${record.originalName}`,
            disabled:
              locked.includes(record.id) ||
              record.status !== 'READY' ||
              (selected.length >= 20 && !selected.includes(record.id)),
            name: record.originalName,
          }),
        }}
        columns={[
          {
            title: '阅读资料',
            dataIndex: 'originalName',
            render: (name: string) => <span className="reading-document-name">{name}</span>,
          },
          {
            title: '状态',
            width: 105,
            render: (_, row) => <Tag>{row.status === 'READY' ? '可阅读' : '待处理'}</Tag>,
          },
        ]}
        pagination={{
          current: page + 1,
          pageSize: 10,
          total: docs.data?.totalElements ?? 0,
          showSizeChanger: false,
          onChange: (value) => setPage(value - 1),
          hideOnSinglePage: true,
        }}
        locale={{ emptyText: '暂无资料，请先在知识库中上传并等待处理完成。' }}
      />
    </div>
  );
}

export function ReadingMarkdown({
  content,
  sources,
  onSource,
}: {
  content: string;
  sources: ReadingSource[];
  onSource: (source: ReadingSource) => void;
}) {
  return (
    <RagAnswerMarkdown
      answer={content}
      disableImages
      citations={sources.map((s) => ({ ...s, quote: s.text }))}
      onCitationClick={(citation) => {
        const found = sources.find((s) => s.citationId === citation.citationId);
        if (found) onSource(found);
      }}
    />
  );
}

export function ReadingSourceDrawer({
  source,
  knowledgeBaseId,
  onClose,
}: {
  source?: ReadingSource;
  knowledgeBaseId: number;
  onClose: () => void;
}) {
  return (
    <Drawer title="原文依据" open={Boolean(source)} onClose={onClose} width={560}>
      {source ? (
        <div className="reading-source-snapshot">
          <Typography.Title level={4}>{source.documentName}</Typography.Title>
          <Typography.Paragraph type="secondary">以下是该结果保存的原文快照。</Typography.Paragraph>
          <div className="reading-tags">
            <Tag>{source.citationId}</Tag>
            <Tag>片段 {source.chunkIndex + 1}</Tag>
            {source.pageFrom !== null ? (
              <Tag>
                PDF 文件页 {source.pageFrom}–{source.pageTo}
              </Tag>
            ) : null}
            <Tag title={source.documentVersion}>资料版本 {source.documentVersion.slice(0, 12)}</Tag>
          </div>
          {source.sectionTitle ? <p>{source.sectionTitle}</p> : null}
          <blockquote>{source.text}</blockquote>
          <Link to={`/knowledge-bases/${knowledgeBaseId}/documents/${source.documentId}`}>
            打开当前版本的文档
          </Link>
        </div>
      ) : null}
    </Drawer>
  );
}
