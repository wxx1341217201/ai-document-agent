import { Alert, Button, Space, Typography } from 'antd';
import { Link } from 'react-router-dom';
import type { ExecutionResponse, RagCitation } from '../../api/contracts';
import { RequestErrorAlert, StatusTag } from '../../components/feedback';
import { useExecutionProgress } from '../progress/useExecutionProgress';
import { ChatMessage } from './ChatMessage';
import { executionMessage } from './execution-message';
import { executionDetailUrl } from '../execution/execution-state';

export function AgentExecutionMessage({
  assistantMessageId,
  question,
  execution: initialExecution,
  isRefreshing,
  refreshError,
  refresh,
  onCitationClick,
  onShowExecution,
}: {
  assistantMessageId: string;
  question: string;
  execution: ExecutionResponse;
  isRefreshing: boolean;
  refreshError: unknown;
  refresh: () => void;
  onCitationClick: (citation: RagCitation) => void;
  onShowExecution: () => void;
}) {
  const query = useExecutionProgress(
    initialExecution.knowledgeBaseId,
    initialExecution.executionId,
    {
      initialData: initialExecution,
    },
  );
  const execution = query.data ?? initialExecution;
  const answer = executionMessage(execution);
  return (
    <>
      <article className="chat-message chat-message-user" aria-label="提交到执行任务的问题">
        <Typography.Text strong>你</Typography.Text>
        <div className="chat-user-content">{question}</div>
        <Typography.Text type="secondary">已提交到执行任务</Typography.Text>
      </article>
      <section
        aria-label="Agent 执行回复"
        data-assistant-message-id={assistantMessageId}
        data-execution-id={execution.executionId}
      >
        <Space wrap className="chat-message-meta">
          <StatusTag status={execution.status} />
          <Button size="small" onClick={onShowExecution}>
            查看执行步骤
          </Button>
          <Link to={executionDetailUrl(execution.executionId, execution.knowledgeBaseId)}>
            打开任务详情
          </Link>
        </Space>
        <details className="message-details">
          <summary>任务信息</summary>
          <Space wrap>
            <Typography.Text copyable>{execution.executionId}</Typography.Text>
            <Button aria-label="刷新执行状态" size="small" onClick={refresh} loading={isRefreshing}>
              刷新执行状态
            </Button>
          </Space>
        </details>
        {refreshError ? <RequestErrorAlert error={refreshError} /> : null}
        {['PENDING', 'RUNNING', 'PAUSED'].includes(execution.status) ? (
          <Typography.Paragraph type="secondary" role="status">
            {query.progress.event?.payload.summary}
            {query.progress.gap
              ? ' 进度事件有缺口，正在同步任务状态。'
              : query.progress.connection === 'subscribed'
                ? ' 正在接收实时进度。'
                : ' 实时连接暂不可用，正在自动刷新任务状态。'}
          </Typography.Paragraph>
        ) : null}
        {answer ? (
          <ChatMessage
            message={answer}
            agentMode={execution.agentMode}
            onCitationClick={onCitationClick}
          />
        ) : (
          <article className="chat-message chat-message-assistant" aria-label="待回复的 AI 消息">
            <Typography.Text strong>AI 助手</Typography.Text>
            {execution.status === 'SUCCEEDED' ? (
              <Alert type="warning" showIcon message="执行已完成，但服务未返回可展示的回答。" />
            ) : execution.status === 'FAILED' ? (
              <Alert type="error" showIcon message="任务执行失败，请核对问题后再发送。" />
            ) : execution.status === 'CANCELLED' ? (
              <Typography.Paragraph>任务已取消。</Typography.Paragraph>
            ) : execution.status === 'PAUSED' ? (
              <Typography.Paragraph>任务已暂停，等待恢复。</Typography.Paragraph>
            ) : (
              <Typography.Paragraph>等待任务完成，状态会自动更新。</Typography.Paragraph>
            )}
          </article>
        )}
      </section>
    </>
  );
}
