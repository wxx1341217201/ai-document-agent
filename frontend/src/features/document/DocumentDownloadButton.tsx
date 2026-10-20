import { DownloadOutlined } from '@ant-design/icons';
import { useMutation } from '@tanstack/react-query';
import { Button, Space } from 'antd';
import type { DocumentRecord } from '../../api/contracts';
import { documentPreviewApi } from '../../api/document-preview.api';
import { RequestErrorAlert } from '../../components/feedback';
import { safeDownloadName } from './document-utils';

export function DocumentDownloadButton({ document }: { document: DocumentRecord }) {
  const download = useMutation({
    retry: false,
    mutationFn: () => documentPreviewApi.file(document.knowledgeBaseId, document.id),
    onSuccess: (file) => {
      const url = URL.createObjectURL(file);
      const link = window.document.createElement('a');
      link.href = url;
      link.download = safeDownloadName(document.originalName);
      window.document.body.append(link);
      link.click();
      link.remove();
      setTimeout(() => URL.revokeObjectURL(url), 1_000);
    },
  });
  return (
    <Space direction="vertical">
      <Button
        icon={<DownloadOutlined />}
        disabled={document.status === 'DELETED'}
        loading={download.isPending}
        onClick={() => download.mutate()}
      >
        下载原文件
      </Button>
      {download.isError ? <RequestErrorAlert error={download.error} /> : null}
    </Space>
  );
}
