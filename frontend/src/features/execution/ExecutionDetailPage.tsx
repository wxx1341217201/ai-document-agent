import { Alert, Button, Card, Descriptions, Modal, Space, Typography } from 'antd';
import { useRef, useState } from 'react';
import { Link, useParams, useSearchParams } from 'react-router-dom';
import type { RagCitation } from '../../api/contracts';
import { normalizeApiError } from '../../api/client';
import { isUuid } from '../../api/progress.contracts';
import {
  ErrorResult,
  PageError,
  PageLoading,
  RequestErrorAlert,
  StatusTag,
} from '../../components/feedback';
import { PageHeader } from '../../components/layout/PageHeader';
import { parsePositiveRouteId } from '../../utils/route-params';
import { formatDateTime } from '../../utils/format';
import { ExecutionDetails } from '../chat/AgentPanel';
import { executionMessage } from '../chat/execution-message';
import { ChatMessage } from '../chat/ChatMessage';
import { agentModeLabels } from '../chat/agent-modes';
import { CitationDrawer } from '../knowledge-base/CitationDrawer';
import { useExecutionProgress } from '../progress/useExecutionProgress';
import { useExecutionAction } from './execution.hooks';
import { executionActions } from './execution-state';

export function ExecutionDetailPage() {
  const { executionId } = useParams();
  const [search] = useSearchParams();
  const knowledgeBaseId = parsePositiveRouteId(search.get('knowledgeBaseId') ?? undefined);
  const valid = Boolean(knowledgeBaseId && isUuid(executionId));
  const query = useExecutionProgress(knowledgeBaseId, valid ? executionId : undefined);
  const action = useExecutionAction(knowledgeBaseId ?? -1, executionId ?? '');
  const [cancelOpen, setCancelOpen] = useState(false);
  const [citation, setCitation] = useState<RagCitation>();
  const sending = useRef(false);

  async function perform(kind: 'resume' | 'cancel') {
    if (!query.data || sending.current) return;
    const allowed = executionActions(query.data);
    if (kind === 'resume' ? !allowed.canResume : !allowed.canCancel) return;
    sending.current = true;
    try {
      await action.mutateAsync(kind);
      setCancelOpen(false);
    } catch {
      // Mutation exposes the error and synchronizes REST before allowing another action.
    } finally {
      sending.current = false;
    }
  }

  if (!valid)
    return (
      <ErrorResult
        status="404"
        title="任务地址无效"
        subTitle="任务 UUID 和 knowledgeBaseId 必须有效，请从任务中心重新打开。"
        action={<Link to="/agent-executions">返回任务中心</Link>}
      />
    );
  if (query.isLoading) return <PageLoading label="正在恢复执行任务…" />;
  if (!query.data)
    return (
      <PageError
        error={query.error}
        title="执行任务无法加载"
        onRetry={() => void query.refetch()}
      />
    );
  const execution = query.data;
  const allowed = executionActions(execution);
  const answer = executionMessage(execution);
  const conflict = action.error && normalizeApiError(action.error).httpStatus === 409;
  const expired =
    action.error &&
    normalizeApiError(action.error).code === 'EXECUTION_EXPIRED' &&
    execution.result == null;
  return (
    <>
      <PageHeader
        title="Agent 执行详情"
        description={execution.executionId}
        actions={
          <Space wrap>
            <Link to="/agent-executions">返回任务中心</Link>
            <Button loading={query.isFetching} onClick={() => void query.refetch()}>
              刷新执行状态
            </Button>
            <Button
              type="primary"
              disabled={!allowed.canResume || query.isError || action.isPending || Boolean(expired)}
              loading={action.isPending && action.variables === 'resume'}
              onClick={() => void perform('resume')}
            >
              Resume · 恢复任务
            </Button>
            <Button
              danger
              disabled={!allowed.canCancel || query.isError || action.isPending}
              onClick={() => {
                action.reset();
                setCancelOpen(true);
              }}
            >
              Cancel · 取消任务
            </Button>
          </Space>
        }
      />
      {query.isError ? <RequestErrorAlert error={query.error} /> : null}
      {conflict ? (
        <Alert
          className="workspace-card"
          type="warning"
          showIcon
          message={expired ? '恢复期限已过，任务无法恢复' : '任务状态已变化，或另一请求已恢复任务'}
          description={
            expired
              ? '服务端拒绝了过期恢复请求，当前任务没有可完成的结果检查点。'
              : '已重新读取服务端状态，请核对后再操作；恢复请求不会自动重试。'
          }
        />
      ) : action.error ? (
        <RequestErrorAlert error={action.error} />
      ) : null}
      {action.isSuccess ? (
        <Alert
          className="workspace-card"
          type="success"
          showIcon
          message={
            action.variables === 'resume' ? '恢复请求已接受' : '取消请求已接受，以服务端状态为准'
          }
        />
      ) : null}
      <Card className="workspace-card" title="任务信息">
        <Descriptions column={{ xs: 1, md: 2 }}>
          <Descriptions.Item label="状态">
            <StatusTag status={execution.status} />
          </Descriptions.Item>
          <Descriptions.Item label="模式">{agentModeLabels[execution.agentMode]}</Descriptions.Item>
          <Descriptions.Item label="知识库">
            <Link to={`/knowledge-bases/${knowledgeBaseId}`}>{knowledgeBaseId}</Link>
          </Descriptions.Item>
          <Descriptions.Item label="会话">
            {execution.conversationId ? (
              <Link to={`/knowledge-bases/${knowledgeBaseId}/chat/${execution.conversationId}`}>
                {execution.conversationId}
              </Link>
            ) : (
              '无关联会话'
            )}
          </Descriptions.Item>
          <Descriptions.Item label="创建时间">
            {formatDateTime(execution.createdAt)}
          </Descriptions.Item>
          <Descriptions.Item label="更新时间">
            {formatDateTime(execution.updatedAt)}
          </Descriptions.Item>
          <Descriptions.Item label="恢复期限">
            {formatDateTime(execution.deadlineAt)}（服务端校验）
          </Descriptions.Item>
          <Descriptions.Item label="版本">{execution.version}</Descriptions.Item>
        </Descriptions>
      </Card>
      <Card className="workspace-card" title="步骤与工具">
        <ExecutionDetails
          key={execution.executionId}
          initialExecution={execution}
          onCitationClick={setCitation}
        />
      </Card>
      <Card title="执行结果">
        {answer ? (
          <ChatMessage
            message={answer}
            agentMode={execution.agentMode}
            onCitationClick={setCitation}
          />
        ) : (
          <Typography.Paragraph type="secondary">
            {execution.status === 'SUCCEEDED'
              ? '服务端未返回可展示的最终回答。'
              : '任务尚无成功完成的结果。'}
          </Typography.Paragraph>
        )}
      </Card>
      <CitationDrawer
        knowledgeBaseId={knowledgeBaseId!}
        citation={citation}
        onClose={() => setCitation(undefined)}
      />
      <Modal
        title="确认取消执行任务"
        open={cancelOpen}
        okText="确认取消任务"
        okType="danger"
        cancelText="返回"
        confirmLoading={action.isPending}
        okButtonProps={{ disabled: !allowed.canCancel || query.isError }}
        onCancel={() => {
          if (!action.isPending) setCancelOpen(false);
        }}
        onOk={() => void perform('cancel')}
        destroyOnHidden
      >
        <Typography.Paragraph>
          运行中的任务会先请求取消，待服务端协作终止；取消后的任务无法 Resume。
        </Typography.Paragraph>
        {action.error ? <RequestErrorAlert error={action.error} /> : null}
      </Modal>
    </>
  );
}
