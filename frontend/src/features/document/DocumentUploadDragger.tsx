import { InboxOutlined } from '@ant-design/icons';
import { Typography, Upload, message } from 'antd';
import type { UploadProps } from 'antd';
import { useState } from 'react';
import { RequestErrorAlert } from '../../components/feedback';
import { useUploadDocument } from './document.hooks';
import { validateDocumentFile } from './document-utils';

export function DocumentUploadDragger({ knowledgeBaseId }: { knowledgeBaseId: number }) {
  const uploadMutation = useUploadDocument(knowledgeBaseId);
  const [requestError, setRequestError] = useState<unknown>();
  const [pendingUploads, setPendingUploads] = useState(0);

  const beforeUpload: UploadProps['beforeUpload'] = (file) => {
    const validationMessage = validateDocumentFile(file);
    if (validationMessage) {
      message.error(validationMessage);
      return Upload.LIST_IGNORE;
    }
    return true;
  };

  const customRequest: NonNullable<UploadProps['customRequest']> = (options) => {
    const file = options.file as File;
    setRequestError(undefined);
    setPendingUploads((count) => count + 1);
    // Each promise settles its own queue item. Per-mutate observer callbacks can
    // be replaced by the next file's mutation when multiple files upload together.
    void uploadMutation
      .mutateAsync({
        file,
        onProgress: (percent) => options.onProgress?.({ percent }, file),
      })
      .then((document) => {
        options.onSuccess?.(document, file);
        message.success(`“${document.originalName}”已上传`);
      })
      .catch((error: unknown) => {
        setRequestError(error);
        options.onError?.(error as Error);
      })
      .finally(() => setPendingUploads((count) => count - 1));

    return {
      abort: () => undefined,
    };
  };

  return (
    <section className="document-upload-panel" aria-label="上传文档">
      {requestError ? <RequestErrorAlert error={requestError} /> : null}
      {pendingUploads > 0 ? (
        <Typography.Paragraph role="status">上传中（{pendingUploads}）</Typography.Paragraph>
      ) : null}
      <Upload.Dragger
        name="file"
        accept=".pdf,.docx,.txt"
        multiple
        customRequest={customRequest}
        beforeUpload={beforeUpload}
        showUploadList={{ showRemoveIcon: false }}
        disabled={pendingUploads > 0}
      >
        <p className="ant-upload-drag-icon">
          <InboxOutlined />
        </p>
        <p className="ant-upload-text">拖拽 PDF、DOCX 或 TXT 到此处，或点击选择文件</p>
        <p className="ant-upload-hint">单个文件最大 20 MB，上传后可在下方查看处理进度。</p>
      </Upload.Dragger>
    </section>
  );
}
