import { RobotOutlined, SendOutlined } from '@ant-design/icons';
import { Alert, Button, Drawer, Grid, Input, Space, Spin, Tag, Typography } from 'antd';
import { useEffect, useRef, useState } from 'react';
import type { Conversation, RagCitation } from '../../api/contracts';
import { EmptyState, PageError, PageLoading, RequestErrorAlert } from '../../components/feedback';
import { CitationDrawer } from '../knowledge-base/CitationDrawer';
import { agentModeLabels } from './agent-modes';
import { useSendAgentMessage } from './agent.hooks';
import { AgentExecutionMessage } from './AgentExecutionMessage';
import { AgentPanel } from './AgentPanel';
import { ChatMessage } from './ChatMessage';
import { useConversationMessages } from './conversation.hooks';

export function ChatThread({
  conversation,
  initialMessage,
  onInitialMessageConsumed,
}: {
  conversation: Conversation;
  initialMessage?: { id: string; content: string };
  onInitialMessageConsumed?: () => void;
}) {
  const history = useConversationMessages(conversation.id);
  const { mutation, pendingMessage, executionMessages, isBusy, sendMessage, submissionNotice } =
    useSendAgentMessage(conversation);
  const [draft, setDraft] = useState(initialMessage?.content ?? '');
  const [citation, setCitation] = useState<RagCitation>();
  const [agentPanelOpen, setAgentPanelOpen] = useState(false);
  const [desktopPanelExpanded, setDesktopPanelExpanded] = useState(false);
  const [selectedExecutionId, setSelectedExecutionId] = useState<string>();
  const desktopPanel = Grid.useBreakpoint().xl;
  const showDesktopPanel = desktopPanel && desktopPanelExpanded;
  const hasAsyncMode = ['REACT', 'PLAN_EXECUTE_REFLECT'].includes(conversation.agentMode);
  const consumedInitialMessage = useRef<string | undefined>(undefined);
  const submitting = useRef(false);
  const transcript = useRef<HTMLDivElement>(null);
  const followLatest = useRef(true);
  const isSending = mutation.isPending || isBusy;
  const content = draft.trim();
  const canSend = history.isSuccess && !isSending && content.length > 0 && content.length <= 2000;

  useEffect(() => {
    if (
      !initialMessage ||
      consumedInitialMessage.current === initialMessage.id ||
      !history.isSuccess ||
      isSending
    )
      return;
    consumedInitialMessage.current = initialMessage.id;
    onInitialMessageConsumed?.();
    setDraft('');
    void sendMessage({ content: initialMessage.content }).catch(() =>
      setDraft(initialMessage.content),
    );
  }, [initialMessage, history.isSuccess, isSending, onInitialMessageConsumed, sendMessage]);

  useEffect(() => {
    if (followLatest.current && transcript.current) {
      transcript.current.scrollTop = transcript.current.scrollHeight;
    }
  }, [history.data, pendingMessage, executionMessages]);

  async function send() {
    if (!canSend || submitting.current) return;
    submitting.current = true;
    followLatest.current = true;
    setSelectedExecutionId(undefined);
    setDraft('');
    try {
      await sendMessage({ content });
    } catch {
      setDraft(content);
    } finally {
      submitting.current = false;
    }
  }

  function showCitation(selected: RagCitation) {
    setAgentPanelOpen(false);
    setCitation(selected);
  }

  const agentPanel = (
    <AgentPanel
      conversation={conversation}
      executions={executionMessages}
      selectedExecutionId={selectedExecutionId}
      onSelectExecution={setSelectedExecutionId}
      submitting={mutation.isPending}
      latestAssistantMessage={history.data
        ?.filter((message) => message.role === 'ASSISTANT')
        .at(-1)}
      onCitationClick={showCitation}
    />
  );

  return (
    <div
      className={
        showDesktopPanel ? 'chat-thread-workspace chat-thread-with-agent' : 'chat-thread-workspace'
      }
    >
      <section className="chat-thread" aria-label="当前会话">
        <div className="chat-thread-header">
          <Typography.Title level={4}>{conversation.title}</Typography.Title>
          <Space wrap>
            <Tag>{agentModeLabels[conversation.agentMode]}</Tag>
            {hasAsyncMode ? (
              <Button
                aria-label="Agent 面板"
                size="small"
                icon={<RobotOutlined />}
                onClick={() =>
                  desktopPanel
                    ? setDesktopPanelExpanded((value) => !value)
                    : setAgentPanelOpen(true)
                }
              >
                {showDesktopPanel ? '收起运行详情' : '运行详情'}
              </Button>
            ) : null}
          </Space>
        </div>
        <div
          ref={transcript}
          className="chat-transcript"
          aria-label="消息历史"
          onScroll={() => {
            const element = transcript.current;
            if (element)
              followLatest.current =
                element.scrollHeight - element.scrollTop - element.clientHeight < 80;
          }}
        >
          {history.isLoading ? <PageLoading label="正在恢复消息历史…" /> : null}
          {history.isError ? (
            <PageError
              title="消息历史加载失败"
              error={history.error}
              onRetry={() => void history.refetch()}
            />
          ) : null}
          {history.isSuccess &&
          history.data.length === 0 &&
          !isSending &&
          executionMessages.length === 0 ? (
            <EmptyState description="还没有消息，发送第一个问题开始对话。" />
          ) : null}
          {history.data?.map((message) => (
            <ChatMessage
              key={message.id}
              message={message}
              agentMode={conversation.agentMode}
              onCitationClick={showCitation}
            />
          ))}
          {executionMessages.map((message) => (
            <AgentExecutionMessage
              key={message.assistantMessageId}
              {...message}
              onCitationClick={showCitation}
              onShowExecution={() => {
                setSelectedExecutionId(message.execution.executionId);
                if (desktopPanel) setDesktopPanelExpanded(true);
                else setAgentPanelOpen(true);
              }}
            />
          ))}
          {pendingMessage ? (
            <>
              <article className="chat-message chat-message-user" aria-label="正在发送的消息">
                <Typography.Text strong>你</Typography.Text>
                <div className="chat-user-content">{pendingMessage.content}</div>
                <Typography.Text type="secondary">发送中…</Typography.Text>
              </article>
              <article
                className="chat-message chat-message-assistant"
                aria-label="待回复的 AI 消息"
                data-assistant-message-id={'assistant-' + pendingMessage.clientMessageId}
              >
                <Typography.Text strong>AI 助手</Typography.Text>
                <Typography.Paragraph type="secondary">正在提交问题…</Typography.Paragraph>
              </article>
            </>
          ) : null}
          <div role="status" aria-live="polite" className="chat-turn-status">
            {pendingMessage || mutation.isPending ? (
              <Space>
                <Spin size="small" />
                <span>正在等待 AI 回答…</span>
              </Space>
            ) : null}
          </div>
        </div>
        <div className="chat-composer">
          {executionMessages.length > 0 ? (
            <Alert
              type="warning"
              showIcon
              message="执行任务尚未写入会话历史；本页关联仅在当前应用会话中保留，完整刷新后无法恢复。"
            />
          ) : null}
          {mutation.isError ? (
            <div className="chat-send-error">
              <RequestErrorAlert error={mutation.error} />
              <Typography.Paragraph type="secondary">
                请先核对已同步的消息历史，确认未保存后再重新发送。
              </Typography.Paragraph>
            </div>
          ) : null}
          {submissionNotice && executionMessages.length > 0 ? (
            <Alert type="success" message={submissionNotice} showIcon />
          ) : null}
          <label htmlFor={'chat-input-' + conversation.id}>消息</label>
          <Input.TextArea
            id={'chat-input-' + conversation.id}
            value={draft}
            onChange={(event) => setDraft(event.target.value)}
            placeholder="输入问题，Enter 发送，Shift+Enter 换行"
            rows={3}
            maxLength={2000}
            showCount
            disabled={isSending || !history.isSuccess}
            onKeyDown={(event) => {
              if (
                event.key === 'Enter' &&
                !event.shiftKey &&
                !event.nativeEvent.isComposing &&
                event.keyCode !== 229
              ) {
                event.preventDefault();
                void send();
              }
            }}
          />
          <div className="chat-composer-actions">
            <Typography.Text type="secondary">Enter 发送 · Shift+Enter 换行</Typography.Text>
            <Button
              aria-label="发送"
              type="primary"
              icon={<SendOutlined />}
              disabled={!canSend}
              loading={isSending}
              onClick={() => void send()}
            >
              发送
            </Button>
          </div>
        </div>
        <CitationDrawer
          knowledgeBaseId={conversation.knowledgeBaseId}
          citation={citation}
          onClose={() => setCitation(undefined)}
        />
      </section>
      {showDesktopPanel ? <aside className="chat-agent-desktop">{agentPanel}</aside> : null}
      {!desktopPanel ? (
        <Drawer
          title="Agent 执行详情"
          placement="right"
          width="min(420px, 100vw)"
          open={agentPanelOpen}
          onClose={() => setAgentPanelOpen(false)}
          destroyOnHidden
        >
          {agentPanel}
        </Drawer>
      ) : null}
    </div>
  );
}
