import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it, vi } from 'vitest';
import type { RagCitation } from '../../api/contracts';
import { RagAnswerMarkdown } from './RagAnswerMarkdown';

const citation: RagCitation = {
  citationId: 'C1',
  documentId: 101,
  documentName: 'design.pdf',
  chunkId: 1001,
  pageFrom: 5,
  pageTo: 5,
  quote: '消费者会先检查幂等键。',
};

describe('RagAnswerMarkdown', () => {
  it('keeps Markdown formatting, ignores raw HTML, and only makes returned citations interactive', async () => {
    const user = userEvent.setup();
    const onCitationClick = vi.fn();

    render(
      <RagAnswerMarkdown
        answer={'<img src=x onerror=alert(1)> **可靠回答** [C1] [C9]'}
        citations={[citation]}
        onCitationClick={onCitationClick}
      />,
    );

    expect(screen.getByText('可靠回答')).toBeInTheDocument();
    expect(document.querySelector('.rag-answer-markdown img')).toBeNull();
    expect(screen.getByRole('button', { name: '[C1]' })).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: '[C9]' })).not.toBeInTheDocument();

    await user.click(screen.getByRole('button', { name: '[C1]' }));

    expect(onCitationClick).toHaveBeenCalledWith(citation);
  });
});
