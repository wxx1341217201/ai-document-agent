import { useQuery } from '@tanstack/react-query';
import { Alert, Card, Pagination, Space, Typography } from 'antd';
import { useEffect, useState } from 'react';
import type { DocumentRecord } from '../../api/contracts';
import { documentPreviewApi } from '../../api/document-preview.api';
import { queryKeys } from '../../api/query-keys';
import { EmptyState, PageLoading, RequestErrorAlert, RetryButton } from '../../components/feedback';
import { formatCitationPages } from '../../utils/format';
import { useDocumentChunks } from './preview.hooks';

export function DocumentPreview({
  document,
  pageNumber,
  chunkId,
}: {
  document: DocumentRecord;
  pageNumber?: number;
  chunkId?: number;
}) {
  const [page, setPage] = useState(0);
  const pdf = document.extension.toLowerCase() === 'pdf' && document.status !== 'DELETED';
  const file = useQuery({
    queryKey: queryKeys.documents.file(document.knowledgeBaseId, document.id),
    queryFn: ({ signal }) => documentPreviewApi.pdf(document.knowledgeBaseId, document.id, signal),
    enabled: pdf,
    gcTime: 0,
    staleTime: Infinity,
  });
  const chunks = useDocumentChunks(
    document.knowledgeBaseId,
    document.id,
    { page, size: 50 },
    document.status === 'READY',
  );
  return (
    <>
      {pdf ? (
        <Card
          className="workspace-card"
          title={pageNumber ? `PDF 预览 · 第 ${pageNumber} 页` : 'PDF 预览'}
        >
          {file.isLoading ? (
            <PageLoading label="正在加载 PDF…" />
          ) : file.isError ? (
            <>
              <RequestErrorAlert error={file.error} />
              <RetryButton onClick={() => void file.refetch()} />
            </>
          ) : file.data ? (
            <PdfObject file={file.data} pageNumber={pageNumber} />
          ) : (
            <EmptyState description="没有可预览的 PDF。" />
          )}
          <Typography.Paragraph type="secondary">
            预览使用浏览器 PDF 阅读器。浏览器不支持嵌入或页码定位时，可下载原文件查看。
          </Typography.Paragraph>
        </Card>
      ) : null}
      <Card
        title={
          document.extension.toLowerCase() === 'docx' ? 'DOCX 抽取文本' : '文档片段 / 抽取文本'
        }
      >
        {chunkId ? (
          <Typography.Paragraph type="secondary">已标记当前页中的引用片段。</Typography.Paragraph>
        ) : null}
        {document.status !== 'READY' ? (
          <EmptyState description="文档处理完成后可查看正文。" />
        ) : chunks.isLoading ? (
          <PageLoading label="正在加载文档片段…" />
        ) : chunks.isError ? (
          <>
            <RequestErrorAlert error={chunks.error} />
            <Alert type="info" message="暂时无法加载正文，可尝试下载原文件。" />
            <RetryButton onClick={() => void chunks.refetch()} />
          </>
        ) : chunks.data ? (
          <>
            {chunks.data.content.length === 0 ? (
              <EmptyState description="服务端未返回文档片段。" />
            ) : (
              <ol className="document-chunks">
                {chunks.data.content.map((chunk) => (
                  <li
                    key={chunk.chunkId}
                    className={
                      chunk.chunkId === chunkId ? 'document-chunk cited-chunk' : 'document-chunk'
                    }
                    data-chunk-id={chunk.chunkId}
                  >
                    <Space wrap>
                      <Typography.Text strong>片段 {chunk.chunkIndex + 1}</Typography.Text>
                      <Typography.Text type="secondary">
                        {formatCitationPages(chunk.pageFrom, chunk.pageTo)}
                      </Typography.Text>
                    </Space>
                    {chunk.sectionTitle ? (
                      <Typography.Paragraph strong>{chunk.sectionTitle}</Typography.Paragraph>
                    ) : null}
                    <Typography.Paragraph className="document-extracted-text">
                      {chunk.content}
                    </Typography.Paragraph>
                  </li>
                ))}
              </ol>
            )}
            <Pagination
              current={page + 1}
              pageSize={50}
              total={chunks.data.totalElements}
              showSizeChanger={false}
              hideOnSinglePage
              onChange={(current) => setPage(current - 1)}
            />
          </>
        ) : (
          <EmptyState description="暂无文档片段。" />
        )}
      </Card>
    </>
  );
}

function PdfObject({ file, pageNumber }: { file: Blob; pageNumber?: number }) {
  const [url, setUrl] = useState('');
  useEffect(() => {
    const objectUrl = URL.createObjectURL(file);
    setUrl(objectUrl);
    return () => URL.revokeObjectURL(objectUrl);
  }, [file]);
  return url ? (
    <object
      aria-label="PDF 文档预览"
      className="document-pdf-preview"
      data={`${url}#page=${pageNumber ?? 1}`}
      type="application/pdf"
    >
      <p>浏览器无法显示 PDF，请使用“下载原文件”。</p>
    </object>
  ) : (
    <PageLoading label="正在准备 PDF 预览…" />
  );
}
