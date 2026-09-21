import type { DocumentStatus } from '../../api/contracts';
import { isDocumentProcessing } from '../../utils/document-status';

export const MAX_DOCUMENT_FILE_BYTES = 20 * 1024 * 1024;
const allowedExtensions = new Set(['pdf', 'docx', 'txt']);

export function validateDocumentFile(file: Pick<File, 'name' | 'size'>): string | undefined {
  const extension = file.name.split('.').pop()?.toLocaleLowerCase();
  if (!extension || !allowedExtensions.has(extension)) {
    return '仅支持 PDF、DOCX 和 TXT 文件。';
  }
  if (file.size <= 0) {
    return '不允许上传空文件。';
  }
  if (file.size > MAX_DOCUMENT_FILE_BYTES) {
    return '文件不能超过 20 MB（当前服务端配置）。';
  }
  return undefined;
}

export function processingStage(status: DocumentStatus): { percent: number; label: string } {
  switch (status) {
    case 'UPLOADED':
      return { percent: 15, label: '上传完成，等待处理' };
    case 'QUEUED':
      return { percent: 30, label: '已进入处理队列' };
    case 'PROCESSING':
      return { percent: 60, label: '正在解析和建立索引' };
    case 'RETRYING':
      return { percent: 45, label: '正在重新处理' };
    case 'READY':
      return { percent: 100, label: '处理完成' };
    case 'FAILED':
      return { percent: 100, label: '处理失败' };
    case 'DELETED':
      return { percent: 100, label: '已删除' };
  }
}

export function shouldPollDocumentStatus(status: DocumentStatus): boolean {
  return isDocumentProcessing(status);
}
