import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { render, screen } from '@testing-library/react';
import { ConfigProvider } from 'antd';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { getDocument } from '../../test/mocks/fixtures';
import { DocumentPreview } from './DocumentPreview';
import { safeDownloadName } from './document-utils';

describe('document preview', { timeout: 60_000 }, () => {
  afterEach(() => vi.unstubAllGlobals());
  it('creates and releases a verified PDF Blob URL and uses only validated page numbers', async () => {
    const create = vi.fn(() => 'blob:mock-pdf');
    const revoke = vi.fn();
    const original = URL;
    vi.stubGlobal(
      'URL',
      Object.assign(class extends original {}, {
        createObjectURL: create,
        revokeObjectURL: revoke,
      }),
    );
    const client = new QueryClient({ defaultOptions: { queries: { retry: false, gcTime: 0 } } });
    const { unmount } = render(
      <QueryClientProvider client={client}>
        <ConfigProvider theme={{ token: { motion: false } }}>
          <DocumentPreview document={getDocument(1, 101)!} pageNumber={2} chunkId={1001} />
        </ConfigProvider>
      </QueryClientProvider>,
    );
    expect(await screen.findByLabelText('PDF 文档预览')).toHaveAttribute(
      'data',
      'blob:mock-pdf#page=2',
    );
    expect(await screen.findByText('Mock 引用：幂等键避免重复处理。')).toBeInTheDocument();
    expect(document.querySelector('[data-chunk-id="1001"]')).toHaveClass('cited-chunk');
    unmount();
    expect(revoke).toHaveBeenCalledWith('blob:mock-pdf');
  });
  it('removes paths, control characters and unsafe filename characters for local downloads', () => {
    expect(safeDownloadName('../bad\\name\u0000.pdf')).toBe('.._bad_name_.pdf');
  });
});
