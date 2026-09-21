import { InboxOutlined } from '@ant-design/icons';
import { Upload, message } from 'antd';
import type { UploadProps } from 'antd';
import { useState } from 'react';
import { RequestErrorAlert } from '../../components/feedback';
import { useUploadDocument } from './document.hooks';
import { validateDocumentFile } from './document-utils';

export function DocumentUploadDragger({ knowledgeBaseId }: { knowledgeBaseId: number }) {
  const uploadMutation = useUploadDocument(knowledgeBaseId);
  const [requestError, setRequestError] = useState<unknown>();

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
    uploadMutation.mutate(
      {
        file,
        onProgress: (percent) => options.onProgress?.({ percent }, file),
      },
      {
        onSuccess: (document) => {
          options.onSuccess?.(document, file);
          message.success(`“${document.originalName}”已上传`);
        },
        onError: (error) => {
          setRequestError(error);
          options.onError?.(error as Error);
        },
      },
    );

    return {
      abort: () => undefined,
    };
  };

  return (
    <section className="document-upload-panel" aria-label="上传文档">
      {requestError ? <RequestErrorAlert error={requestError} /> : null}
      <Upload.Dragger
        name="file"
        accept=".pdf,.docx,.txt"
        multiple
        customRequest={customRequest}
        beforeUpload={beforeUpload}
        showUploadList={{ showRemoveIcon: false }}
        disabled={uploadMutation.isPending}
      >
        <p className="ant-upload-drag-icon">
          <InboxOutlined />
        </p>
        <p className="ant-upload-text">拖拽 PDF、DOCX 或 TXT 到此处，或点击选择文件</p>
        <p className="ant-upload-hint">
          单个文件最大 20 MB；服务端仍会执行类型、内容和重复文件校验。
        </p>
      </Upload.Dragger>
    </section>
  );
}
