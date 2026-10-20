import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { ConfigProvider } from 'antd';
import { describe, expect, it, vi } from 'vitest';
import { ApiClientError } from '../../api/client';
import { KnowledgeBaseFormModal } from './KnowledgeBaseFormModal';

describe('knowledge-base form', { timeout: 60_000 }, () => {
  it('rejects blank names and submits valid labeled fields', async () => {
    const submit = vi.fn();
    render(
      <ConfigProvider theme={{ token: { motion: false } }}>
        <KnowledgeBaseFormModal open mode="create" onCancel={vi.fn()} onSubmit={submit} />
      </ConfigProvider>,
    );
    fireEvent.change(screen.getByLabelText('名称'), { target: { value: '   ' } });
    fireEvent.click(screen.getByRole('button', { name: /创\s*建/ }));
    expect(await screen.findByText('请输入知识库名称。')).toBeInTheDocument();
    expect(submit).not.toHaveBeenCalled();
    fireEvent.change(screen.getByLabelText('名称'), { target: { value: '研究资料' } });
    fireEvent.change(screen.getByLabelText(/^描述/), { target: { value: '实验记录' } });
    fireEvent.click(screen.getByRole('button', { name: /创\s*建/ }));
    await waitFor(() =>
      expect(submit).toHaveBeenCalledWith({ name: '研究资料', description: '实验记录' }),
    );
  });
  it('shows actionable 409 errors and preserves edit values', () => {
    render(
      <ConfigProvider theme={{ token: { motion: false } }}>
        <KnowledgeBaseFormModal
          open
          mode="edit"
          initialValues={{ name: '原知识库' }}
          error={
            new ApiClientError({
              httpStatus: 409,
              code: 'KNOWLEDGE_BASE_NAME_CONFLICT',
              message: '知识库名称已存在',
            })
          }
          onCancel={vi.fn()}
          onSubmit={vi.fn()}
        />
      </ConfigProvider>,
    );
    expect(screen.getByLabelText('名称')).toHaveValue('原知识库');
    expect(screen.getByText('知识库名称已存在')).toBeInTheDocument();
  });
});
