import { SendOutlined } from '@ant-design/icons';
import { Button, Input, Typography } from 'antd';
import { useState } from 'react';
import { RequestErrorAlert } from '../../components/feedback';

export function NewConversationComposer({
  loading,
  error,
  onSend,
}: {
  loading: boolean;
  error: unknown;
  onSend: (content: string) => void;
}) {
  const [draft, setDraft] = useState('');
  const content = draft.trim();
  const canSend = !loading && content.length > 0 && content.length <= 2000;
  function send() {
    if (canSend) onSend(content);
  }

  return (
    <section className="chat-new-conversation" aria-label="新会话">
      <div className="chat-new-intro">
        <h2>想了解文档中的什么？</h2>
        <Typography.Paragraph type="secondary">
          直接提问，按需查看原文引用。会话将自动保存。
        </Typography.Paragraph>
      </div>
      <div className="chat-composer chat-new-composer">
        {error ? <RequestErrorAlert error={error} /> : null}
        <label htmlFor="new-chat-input">消息</label>
        <Input.TextArea
          id="new-chat-input"
          value={draft}
          onChange={(event) => setDraft(event.target.value)}
          placeholder="输入你想了解的问题"
          rows={4}
          maxLength={2000}
          showCount
          disabled={loading}
          onKeyDown={(event) => {
            if (
              event.key === 'Enter' &&
              !event.shiftKey &&
              !event.nativeEvent.isComposing &&
              event.keyCode !== 229
            ) {
              event.preventDefault();
              send();
            }
          }}
        />
        <div className="chat-composer-actions">
          <Typography.Text type="secondary">Enter 发送 · Shift+Enter 换行</Typography.Text>
          <Button
            aria-label="发送"
            type="primary"
            icon={<SendOutlined />}
            loading={loading}
            disabled={!canSend}
            onClick={send}
          >
            发送
          </Button>
        </div>
      </div>
    </section>
  );
}
