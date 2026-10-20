import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { ConfigProvider } from 'antd';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { ApiClientError } from '../../api/client';
import { documentApi } from '../../api/document.api';
import { getDocument } from '../../test/mocks/fixtures';
import { DocumentUploadDragger } from './DocumentUploadDragger';

describe(
  'upload queue (API service Mock; real multipart is covered by Playwright)',
  { timeout: 60_000 },
  () => {
    afterEach(() => vi.restoreAllMocks());
    it('completes each concurrent TXT/PDF queue item and exposes failure for a rejected file', async () => {
      const releases: (() => void)[] = [];
      const names: string[] = [];
      vi.spyOn(documentApi, 'upload').mockImplementation((_kbId, file) => {
        names.push(file.name);
        return new Promise((resolve, reject) =>
          releases.push(() => {
            if (file.name === 'failed.txt')
              reject(
                new ApiClientError({
                  code: 'DOCUMENT_INVALID',
                  httpStatus: 400,
                  message: '文件内容无效',
                }),
              );
            else resolve({ ...getDocument(1, 101)!, originalName: file.name });
          }),
        );
      });
      const client = new QueryClient({ defaultOptions: { queries: { retry: false, gcTime: 0 } } });
      const { container } = render(
        <QueryClientProvider client={client}>
          <ConfigProvider theme={{ token: { motion: false } }}>
            <DocumentUploadDragger knowledgeBaseId={1} />
          </ConfigProvider>
        </QueryClientProvider>,
      );
      fireEvent.change(container.querySelector('input[type="file"]')!, {
        target: {
          files: [
            new File(['text'], 'notes.txt', { type: 'text/plain' }),
            new File(['%PDF-1.4'], 'paper.pdf', { type: 'application/pdf' }),
            new File(['bad'], 'failed.txt', { type: 'text/plain' }),
          ],
        },
      });
      await waitFor(() => expect(releases).toHaveLength(3), { timeout: 10_000 });
      expect(screen.getByText('上传中（3）')).toBeInTheDocument();
      releases[1]();
      releases[0]();
      releases[2]();
      expect(await screen.findByText('文件内容无效', {}, { timeout: 10_000 })).toBeInTheDocument();
      await waitFor(() =>
        expect(container.querySelectorAll('.ant-upload-list-item-done')).toHaveLength(2),
      );
      expect(container.querySelectorAll('.ant-upload-list-item-error')).toHaveLength(1);
      expect(names.sort()).toEqual(['failed.txt', 'notes.txt', 'paper.pdf']);
    });
  },
);
