import { fireEvent, render, screen } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import { mockChatCitation } from '../../test/mocks/conversation-fixtures';
import { ChatMessage, type ChatMessageData } from './ChatMessage';

const base: ChatMessageData = {
  role: 'ASSISTANT',
  content: '**有引用的回答**。[C1]<script>alert(1)</script>',
  citations: [mockChatCitation],
  modelName: null,
  tokenUsage: null,
  createdAt: '2026-10-11T08:30:00',
};
describe('chat message safety and citations', () => {
  it('opens only supplied citations and never executes model HTML', () => {
    const click = vi.fn();
    const { container } = render(
      <ChatMessage message={base} agentMode="RETRIEVAL_FIRST" onCitationClick={click} />,
    );
    fireEvent.click(screen.getByRole('button', { name: '[C1]' }));
    expect(click).toHaveBeenCalledWith(mockChatCitation);
    expect(container.querySelector('script')).toBeNull();
  });
  it('suppresses explicit hidden-thought blocks and displays empty citation/answer states', () => {
    const { rerender } = render(
      <ChatMessage
        message={{ ...base, content: '<think>private reasoning</think>answer', citations: [] }}
        agentMode="REACT"
        onCitationClick={vi.fn()}
      />,
    );
    expect(screen.queryByText(/private reasoning/)).not.toBeInTheDocument();
    rerender(
      <ChatMessage
        message={{ ...base, content: '', citations: [] }}
        agentMode="REACT"
        onCitationClick={vi.fn()}
      />,
    );
    expect(screen.getByText('后端未返回回答内容。')).toBeInTheDocument();
    expect(screen.getByText('本条回答未附引用。')).toBeInTheDocument();
  });
});
