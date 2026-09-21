import { describe, expect, it } from 'vitest';
import { MAX_DOCUMENT_FILE_BYTES, processingStage, validateDocumentFile } from './document-utils';

describe('validateDocumentFile', () => {
  it('accepts an allowed, non-empty file under the server limit', () => {
    expect(validateDocumentFile({ name: 'paper.PDF', size: 1024 })).toBeUndefined();
  });

  it('rejects unsupported, empty, and oversized files before upload', () => {
    expect(validateDocumentFile({ name: 'paper.exe', size: 1024 })).toContain('仅支持');
    expect(validateDocumentFile({ name: 'paper.txt', size: 0 })).toContain('空文件');
    expect(
      validateDocumentFile({ name: 'paper.txt', size: MAX_DOCUMENT_FILE_BYTES + 1 }),
    ).toContain('20 MB');
  });
});

describe('processingStage', () => {
  it('uses a semantic state mapping when granular server progress is unavailable', () => {
    expect(processingStage('READY')).toEqual({ percent: 100, label: '处理完成' });
    expect(processingStage('PROCESSING').label).toContain('解析');
  });
});
