import { Button, Space, Tag, Typography } from 'antd';
import type { ConversationAgentMode, ConversationMessage, RagCitation } from '../../api/contracts';
import { formatDateTime } from '../../utils/format';
import { RagAnswerMarkdown } from '../knowledge-base/RagAnswerMarkdown';
import { agentModeLabels } from './agent-modes';

/** Shared display fields; execution answers have no persisted ConversationMessage ID/sequence. */
export type ChatMessageData = Pick<
  ConversationMessage,
  'role' | 'content' | 'citations' | 'modelName' | 'tokenUsage' | 'createdAt'
>;

export function ChatMessage({
  message,
  agentMode,
  onCitationClick,
}: {
  message: ChatMessageData;
  agentMode: ConversationAgentMode;
  onCitationClick: (citation: RagCitation) => void;
}) {
  const isUser = message.role === 'USER';
  return (
    <article
      className={'chat-message ' + (isUser ? 'chat-message-user' : 'chat-message-assistant')}
    >
      <div className="chat-message-heading">
        <Typography.Text strong>{isUser ? '你' : 'AI 助手'}</Typography.Text>
        <Typography.Text type="secondary">{formatDateTime(message.createdAt)}</Typography.Text>
      </div>
      {isUser ? (
        <div className="chat-user-content">{message.content}</div>
      ) : (
        <>
          {/<\/?think\b/i.test(message.content) ? (
            <Typography.Text type="secondary">
              服务端回答包含不可展示的推理标记，请重新加载。
            </Typography.Text>
          ) : message.content.trim() ? (
            <RagAnswerMarkdown
              answer={message.content}
              citations={message.citations}
              onCitationClick={onCitationClick}
            />
          ) : (
            <Typography.Text type="secondary">后端未返回回答内容。</Typography.Text>
          )}
          <Space className="chat-message-citations" wrap aria-label="回答引用">
            {message.citations.length === 0 ? (
              <Typography.Text type="secondary">本条回答未附引用。</Typography.Text>
            ) : (
              message.citations.map((citation) => (
                <Button
                  key={citation.citationId + '-' + citation.chunkId}
                  size="small"
                  onClick={() => onCitationClick(citation)}
                >
                  [{citation.citationId}] {citation.documentName}
                </Button>
              ))
            )}
          </Space>
          <details className="message-details">
            <summary>回答信息</summary>
            <Space className="chat-message-meta" wrap>
              <Tag>{agentModeLabels[agentMode]}</Tag>
              {message.modelName ? (
                <Typography.Text type="secondary">{message.modelName}</Typography.Text>
              ) : null}
              {message.tokenUsage?.totalTokens != null ? (
                <Typography.Text type="secondary">
                  {message.tokenUsage.totalTokens} tokens
                </Typography.Text>
              ) : null}
            </Space>
          </details>
        </>
      )}
    </article>
  );
}
