import { ReloadOutlined, RobotOutlined } from '@ant-design/icons';
import { useQueryClient } from '@tanstack/react-query';
import { Alert, Button, Select, Space, Tag, Timeline, Typography } from 'antd';
import { useEffect } from 'react';
import type {
  Conversation,
  ConversationMessage,
  ExecutionResponse,
  ExecutionStepStatus,
  RagCitation,
} from '../../api/contracts';
import { queryKeys } from '../../api/query-keys';
import { EmptyState, PageLoading, RequestErrorAlert, StatusTag } from '../../components/feedback';
import { formatDateTime } from '../../utils/format';
import { useExecutionProgress } from '../progress/useExecutionProgress';
import { agentModeLabels } from './agent-modes';
import { executionMessage } from './execution-message';
import {
  executionVisualization,
  operationLabel,
  safeErrorCode,
  stepKindLabels,
  stepStatusLabels,
} from './execution-visualization';

interface AgentPanelProps {
  conversation: Conversation;
  executions: { execution: ExecutionResponse; question: string }[];
  selectedExecutionId?: string;
  onSelectExecution: (executionId: string) => void;
  submitting: boolean;
  latestAssistantMessage?: ConversationMessage;
  onCitationClick: (citation: RagCitation) => void;
}

export function AgentPanel({
  conversation,
  executions,
  selectedExecutionId,
  onSelectExecution,
  submitting,
  latestAssistantMessage,
  onCitationClick,
}: AgentPanelProps) {
  const selected =
    executions.find(({ execution }) => execution.executionId === selectedExecutionId) ??
    executions.at(-1);
  const synchronous =
    conversation.agentMode === 'RAG' || conversation.agentMode === 'RETRIEVAL_FIRST';
  return (
    <section aria-label="Agent 面板" className="agent-panel">
      <div className="agent-panel-toolbar">
        <div className="agent-panel-heading">
          <Typography.Title level={4}>
            <RobotOutlined /> Agent Execution
          </Typography.Title>
          <Tag>{agentModeLabels[conversation.agentMode]}</Tag>
        </div>
        {executions.length > 1 ? (
          <Select
            aria-label="选择 Agent 执行"
            className="agent-execution-select"
            value={selected?.execution.executionId}
            onChange={onSelectExecution}
            getPopupContainer={(trigger) => trigger.parentElement ?? trigger}
            options={executions.map(({ execution }, index) => ({
              value: execution.executionId,
              label: `第 ${index + 1} 次 · ${execution.executionId.slice(0, 8)}`,
            }))}
          />
        ) : null}
      </div>
      {submitting ? (
        <PageLoading label={synchronous ? '正在等待同步回答…' : '正在提交执行任务…'} />
      ) : null}
      {selected ? (
        <ExecutionDetails
          key={selected.execution.executionId}
          initialExecution={selected.execution}
          question={selected.question}
          onCitationClick={onCitationClick}
        />
      ) : synchronous ? (
        <>
          {latestAssistantMessage && !submitting ? (
            <>
              <Alert type="success" showIcon message="本轮回答已完成" />
              <PanelCitations
                citations={latestAssistantMessage.citations}
                onClick={onCitationClick}
              />
            </>
          ) : null}
          <EmptyState description="同步消息接口未提供步骤与工具摘要，回答及引用显示在消息历史中。" />
        </>
      ) : !submitting ? (
        <EmptyState description="提交问题后，在这里查看执行步骤与工具调用。" />
      ) : null}
    </section>
  );
}

export function ExecutionDetails({
  initialExecution,
  question,
  onCitationClick,
}: {
  initialExecution: ExecutionResponse;
  question?: string;
  onCitationClick: (citation: RagCitation) => void;
}) {
  const queryClient = useQueryClient();
  const query = useExecutionProgress(
    initialExecution.knowledgeBaseId,
    initialExecution.executionId,
    {
      initialData: initialExecution,
    },
  );
  const execution = query.data ?? initialExecution;
  const { steps, toolCalls, currentStep } = executionVisualization(execution);
  const answer = executionMessage(execution);
  const active = ['PENDING', 'RUNNING', 'PAUSED'].includes(execution.status);
  const event = query.progress.event;
  const errorCode = safeErrorCode(execution.errorCode);

  // Event payloads lack operations/dependencies/elapsed time. Re-read the authoritative DTO
  // instead of constructing a plan or tool call from incomplete WebSocket fields.
  useEffect(() => {
    if (
      !event ||
      !['PLAN_CREATED', 'STEP_STARTED', 'STEP_COMPLETED', 'TOOL_COMPLETED'].includes(event.type)
    )
      return;
    void queryClient.invalidateQueries(
      {
        queryKey: queryKeys.executions.detail(
          initialExecution.knowledgeBaseId,
          initialExecution.executionId,
        ),
        exact: true,
      },
      { cancelRefetch: false },
    );
  }, [event, queryClient, initialExecution.knowledgeBaseId, initialExecution.executionId]);

  return (
    <div className="agent-execution-details" data-execution-id={execution.executionId}>
      <Space wrap>
        <StatusTag status={execution.status} />
        {execution.status === 'RUNNING' ? (
          <span className="agent-running-dot" aria-hidden="true" />
        ) : null}
        <Button
          size="small"
          icon={<ReloadOutlined />}
          aria-label={query.isError ? '重试加载 Agent 详情' : '刷新 Agent 详情'}
          loading={query.isFetching}
          onClick={() => void query.refetch()}
        >
          {query.isError ? '重试加载详情' : '刷新详情'}
        </Button>
      </Space>
      <Typography.Text className="agent-execution-id" type="secondary" copyable>
        {execution.executionId}
      </Typography.Text>
      {question ? (
        <Typography.Paragraph className="agent-question">
          <strong>本轮问题：</strong>
          {question}
        </Typography.Paragraph>
      ) : null}
      <Typography.Paragraph type="secondary">
        更新于 {formatDateTime(execution.updatedAt)}
      </Typography.Paragraph>
      {query.isError ? (
        <div className="agent-panel-notice">
          <RequestErrorAlert error={query.error} />
          <Typography.Text type="secondary">
            以下保留最近一次成功读取的状态，可重试加载详情。
          </Typography.Text>
        </div>
      ) : null}
      {active ? (
        <div className="agent-panel-notice" role="status" aria-live="polite">
          {query.progress.gap ? (
            <Alert type="warning" showIcon message="进度事件存在缺口，正在同步详情" />
          ) : query.progress.connection === 'unavailable' ||
            query.progress.connection === 'reconnecting' ? (
            <Alert
              type="warning"
              showIcon
              message="实时进度已降级"
              description={
                query.progress.connection === 'reconnecting'
                  ? '连接中断，正在重试连接；当前通过 REST 自动刷新状态。'
                  : '实时连接暂不可用，当前通过 REST 自动刷新状态。'
              }
            />
          ) : (
            <Typography.Text type="secondary">
              {query.progress.connection === 'subscribed' ? '正在接收实时进度' : '正在连接实时进度'}
            </Typography.Text>
          )}
          {event?.payload.summary ? (
            <Typography.Paragraph type="secondary">{event.payload.summary}</Typography.Paragraph>
          ) : null}
        </div>
      ) : null}
      {execution.cancelRequested && active ? (
        <Alert type="info" showIcon message="已请求取消，等待服务端确认" />
      ) : null}
      {execution.status === 'PAUSED' ? (
        <Alert type="warning" showIcon message="任务已暂停，等待恢复" />
      ) : null}
      {execution.status === 'FAILED' ? (
        <Alert
          type="error"
          showIcon
          message={execution.retryable === true ? '执行失败 · 可重试' : '执行失败 · 当前不可重试'}
          description={errorCode ? `错误码：${errorCode}` : '服务未返回安全错误码。'}
        />
      ) : execution.status === 'CANCELLED' ? (
        <Alert type="info" showIcon message="任务已取消" />
      ) : execution.status === 'SUCCEEDED' ? (
        <Alert type="success" showIcon message="执行已完成" />
      ) : null}

      <section aria-label="执行步骤" className="agent-panel-section">
        <Typography.Title level={5}>
          {execution.agentMode === 'PLAN_EXECUTE_REFLECT'
            ? 'Plan / Step Timeline'
            : 'Step Timeline'}
        </Typography.Title>
        {currentStep ? (
          <Typography.Paragraph type="secondary">当前步骤：{currentStep}</Typography.Paragraph>
        ) : null}
        {steps.length === 0 ? (
          <EmptyState description="服务尚未返回结构化步骤。" />
        ) : (
          <Timeline
            items={steps.map((step) => ({
              color:
                step.status === 'FAILED'
                  ? 'red'
                  : ['COMPLETED', 'SUCCEEDED'].includes(step.status)
                    ? 'green'
                    : step.status === 'RUNNING'
                      ? 'blue'
                      : 'gray',
              dot:
                step.status === 'RUNNING' && execution.status === 'RUNNING' ? (
                  <span className="agent-running-dot" aria-hidden="true" />
                ) : undefined,
              children: (
                <div
                  className={
                    step.stepId === currentStep ? 'agent-step agent-step-current' : 'agent-step'
                  }
                  data-step-id={step.stepId}
                >
                  <Space wrap>
                    <Typography.Text strong>{step.stepId}</Typography.Text>
                    <StepStatusTag status={step.status} />
                  </Space>
                  <div>
                    <Tag>{stepKindLabels[step.kind]}</Tag>
                    {operationLabel(step.operation)}
                  </div>
                  {step.dependsOn.length > 0 ? (
                    <Typography.Paragraph type="secondary">
                      依赖：{step.dependsOn.join('、')}
                    </Typography.Paragraph>
                  ) : null}
                  {step.errorCode ? (
                    <Typography.Text type="danger">错误码：{step.errorCode}</Typography.Text>
                  ) : null}
                </div>
              ),
            }))}
          />
        )}
      </section>
      <section aria-label="工具调用摘要" className="agent-panel-section">
        <Typography.Title level={5}>
          工具调用摘要{toolCalls.length > 0 ? `（${toolCalls.length}）` : ''}
        </Typography.Title>
        {toolCalls.length === 0 ? (
          <EmptyState description="服务尚未返回工具调用摘要。" />
        ) : (
          <ol className="agent-tool-list">
            {toolCalls.map((tool) => (
              <li key={tool.invocationKey} className="agent-tool">
                <Space wrap>
                  <Typography.Text strong>{tool.toolName}</Typography.Text>
                  <StepStatusTag status={tool.status} />
                </Space>
                <div>
                  <Typography.Text type="secondary">
                    {tool.elapsedMillis === null ? '耗时未知' : `${tool.elapsedMillis} ms`}
                  </Typography.Text>
                </div>
                {tool.arguments.length > 0 ? (
                  <dl className="agent-tool-arguments">
                    {tool.arguments.map(({ label, value }) => (
                      <div key={label}>
                        <dt>{label}</dt>
                        <dd>{value}</dd>
                      </div>
                    ))}
                  </dl>
                ) : null}
                {tool.errorCode ? (
                  <Typography.Text type="danger">错误码：{tool.errorCode}</Typography.Text>
                ) : null}
              </li>
            ))}
          </ol>
        )}
      </section>
      {execution.status === 'SUCCEEDED' ? (
        <PanelCitations citations={answer?.citations ?? []} onClick={onCitationClick} />
      ) : null}
    </div>
  );
}

function StepStatusTag({ status }: { status: ExecutionStepStatus }) {
  const { label, color } = stepStatusLabels[status];
  return (
    <Tag color={color} aria-label={`步骤状态：${status}`}>
      {label}
    </Tag>
  );
}

function PanelCitations({
  citations,
  onClick,
}: {
  citations: RagCitation[];
  onClick: (citation: RagCitation) => void;
}) {
  return (
    <section aria-label="执行引用" className="agent-panel-section">
      <Typography.Title level={5}>引用</Typography.Title>
      {citations.length === 0 ? (
        <Typography.Text type="secondary">本次回答未附引用。</Typography.Text>
      ) : (
        <Space direction="vertical" className="agent-panel-citations">
          {citations.map((citation) => (
            <Button
              key={citation.citationId + '-' + citation.chunkId}
              size="small"
              onClick={() => onClick(citation)}
            >
              [{citation.citationId}] {citation.documentName}
            </Button>
          ))}
        </Space>
      )}
    </section>
  );
}
