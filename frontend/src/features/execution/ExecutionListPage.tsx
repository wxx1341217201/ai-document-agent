import { Button, Card, Form, Input, Select, Space, Table, Typography } from 'antd';
import type { ColumnsType } from 'antd/es/table';
import { Link, useNavigate, useSearchParams } from 'react-router-dom';
import { useState } from 'react';
import type { AgentMode, ExecutionStatus, ExecutionSummary } from '../../api/contracts';
import { isUuid } from '../../api/progress.contracts';
import { EmptyState, PageLoading, RequestErrorAlert, StatusTag } from '../../components/feedback';
import { PageHeader } from '../../components/layout/PageHeader';
import { formatDateTime } from '../../utils/format';
import { parsePositiveRouteId } from '../../utils/route-params';
import { agentModeLabels } from '../chat/agent-modes';
import { operationLabel } from '../chat/execution-visualization';
import { useExecutionList } from './execution.hooks';
import { executionDetailUrl } from './execution-state';

const statuses: ExecutionStatus[] = [
  'PENDING',
  'RUNNING',
  'PAUSED',
  'SUCCEEDED',
  'FAILED',
  'CANCELLED',
];
const modes: AgentMode[] = ['RETRIEVAL_FIRST', 'REACT', 'PLAN_EXECUTE_REFLECT'];

export function ExecutionListPage() {
  const [search, setSearch] = useSearchParams();
  const navigate = useNavigate();
  const [bookmarkError, setBookmarkError] = useState(false);
  const page = Math.max(0, (parsePositiveRouteId(search.get('page') ?? undefined) ?? 1) - 1);
  const status = statuses.find((value) => value === search.get('status'));
  const agentMode = modes.find((value) => value === search.get('agentMode'));
  const query = useExecutionList({ page, size: 20, status, agentMode });
  function filter(name: string, value?: string) {
    const next = new URLSearchParams(search);
    next.delete('page');
    if (value) next.set(name, value);
    else next.delete(name);
    setSearch(next);
  }
  const columns: ColumnsType<ExecutionSummary> = [
    {
      title: 'Execution ID',
      dataIndex: 'executionId',
      width: 240,
      render: (id: string, item) => (
        <Space>
          <Link to={executionDetailUrl(id, item.knowledgeBaseId)}>{id.slice(0, 8)}</Link>
          <Typography.Text copyable={{ text: id }} />
        </Space>
      ),
    },
    { title: '模式', dataIndex: 'agentMode', render: (mode: AgentMode) => agentModeLabels[mode] },
    {
      title: '状态',
      dataIndex: 'status',
      render: (value: ExecutionStatus) => <StatusTag status={value} />,
    },
    {
      title: '当前步骤',
      dataIndex: 'currentStep',
      render: (value: string | null) => (value ? operationLabel(value) : '—'),
    },
    { title: '开始时间', dataIndex: 'startedAt', render: formatDateTime },
    { title: '更新时间', dataIndex: 'updatedAt', render: formatDateTime },
    {
      title: '操作',
      key: 'actions',
      render: (_, item) => (
        <Link to={executionDetailUrl(item.executionId, item.knowledgeBaseId)}>查看</Link>
      ),
    },
  ];
  return (
    <>
      <PageHeader
        title="任务记录"
        description="从服务端重新打开执行任务，查看进度、恢复或取消。"
        actions={
          <Button loading={query.isFetching} onClick={() => void query.refetch()}>
            刷新任务
          </Button>
        }
      />
      <details className="workspace-details">
        <summary>按任务编号打开（高级）</summary>
        <Card className="workspace-card" title="打开已知任务">
          <Form
            layout="inline"
            onFinish={(input: { executionId: string; knowledgeBaseId: string }) => {
              const id = input.executionId.trim();
              const kbId = parsePositiveRouteId(input.knowledgeBaseId.trim());
              if (!isUuid(id) || !kbId) {
                setBookmarkError(true);
                return;
              }
              setBookmarkError(false);
              navigate(executionDetailUrl(id, kbId));
            }}
          >
            <Form.Item
              label="Execution ID"
              name="executionId"
              rules={[{ required: true, message: '请输入任务 UUID' }]}
            >
              <Input placeholder="任务 UUID" />
            </Form.Item>
            <Form.Item
              label="知识库 ID"
              name="knowledgeBaseId"
              rules={[{ required: true, message: '请输入知识库 ID' }]}
            >
              <Input inputMode="numeric" />
            </Form.Item>
            <Form.Item>
              <Button htmlType="submit">打开任务</Button>
            </Form.Item>
          </Form>
          {bookmarkError ? (
            <Typography.Paragraph type="danger" role="alert">
              请输入合法的任务 UUID 和正整数知识库 ID。
            </Typography.Paragraph>
          ) : null}
        </Card>
      </details>
      <Card
        title="执行任务"
        extra={
          <Space wrap>
            <Select
              aria-label="筛选任务模式"
              placeholder="全部模式"
              allowClear
              value={agentMode}
              onChange={(value) => filter('agentMode', value)}
              style={{ width: 210 }}
              options={modes.map((mode) => ({ value: mode, label: agentModeLabels[mode] }))}
            />
            <Select
              aria-label="筛选任务状态"
              placeholder="全部状态"
              allowClear
              value={status}
              onChange={(value) => filter('status', value)}
              style={{ width: 150 }}
              options={statuses.map((value) => ({ value, label: <StatusTag status={value} /> }))}
            />
          </Space>
        }
      >
        {query.isLoading ? <PageLoading label="正在加载执行任务…" /> : null}
        {query.isError ? (
          <>
            <RequestErrorAlert error={query.error} />
            <Typography.Paragraph type="secondary">
              任务列表暂时不可用。已有任务可通过聊天中的任务详情链接打开。
            </Typography.Paragraph>
            <Button onClick={() => void query.refetch()}>重试列表</Button>
          </>
        ) : null}
        {query.data ? (
          <Table
            rowKey="executionId"
            columns={columns}
            dataSource={query.data.content}
            scroll={{ x: 1100 }}
            locale={{ emptyText: <EmptyState description="没有符合条件的执行任务。" /> }}
            pagination={{
              current: page + 1,
              pageSize: 20,
              total: query.data.totalElements,
              showSizeChanger: false,
              hideOnSinglePage: true,
              onChange: (current) => {
                const next = new URLSearchParams(search);
                next.set('page', String(current));
                setSearch(next);
              },
            }}
          />
        ) : null}
      </Card>
    </>
  );
}
