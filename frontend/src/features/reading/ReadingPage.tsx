import { BookOutlined, PlusOutlined } from '@ant-design/icons';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import {
  Button,
  Card,
  Checkbox,
  Empty,
  Form,
  Input,
  Pagination,
  Space,
  Table,
  Typography,
} from 'antd';
import { useRef, useState } from 'react';
import { Link, useNavigate, useParams, useSearchParams } from 'react-router-dom';
import { knowledgeBaseApi } from '../../api/knowledge-base.api';
import { readingApi, type CreateReadingTask, type ReadingTaskSummary } from '../../api/reading.api';
import { PageHeader } from '../../components/layout/PageHeader';
import { ErrorResult, PageLoading } from '../../components/feedback';
import { parsePositiveRouteId } from '../../utils/route-params';
import { formatDateTime } from '../../utils/format';
import { ReadingTaskDetail } from './ReadingTaskDetail';
import {
  CompletionTag,
  ReadingError,
  ReadingSourcePicker,
  ReadingStatusTag,
} from './reading-components';

export function ReadingPage() {
  const { kbId, taskId } = useParams();
  const kb = parsePositiveRouteId(kbId);
  if (!kbId) return <ReadingLibraryPicker />;
  if (!kb || (taskId && !/^[a-f0-9]{8}-(?:[a-f0-9]{4}-){3}[a-f0-9]{12}$/i.test(taskId)))
    return <ErrorResult status="404" title="阅读地址无效" subTitle="请从知识库打开阅读空间。" />;
  return taskId ? (
    <ReadingTaskDetail key={taskId} knowledgeBaseId={kb} taskId={taskId} />
  ) : (
    <ReadingWorkspace key={kb} knowledgeBaseId={kb} />
  );
}

function ReadingLibraryPicker() {
  const [page, setPage] = useState(0);
  const libraries = useQuery({
    queryKey: ['reading-libraries', page],
    queryFn: () => knowledgeBaseApi.list({ page, size: 12 }),
  });
  return (
    <>
      <PageHeader
        title="阅读笔记"
        description="从一份熟悉的资料开始，让每一次阅读留下有依据的思考。"
      />
      <ReadingError error={libraries.error} />
      {libraries.isLoading ? <PageLoading /> : null}
      <div className="reading-library-grid">
        {libraries.data?.content.map((kb) => (
          <Card key={kb.id}>
            <BookOutlined className="reading-library-icon" />
            <Typography.Title level={3}>{kb.name}</Typography.Title>
            <Typography.Paragraph type="secondary" ellipsis={{ rows: 2 }}>
              {kb.description || '选择阅读资料，围绕主题整理笔记。'}
            </Typography.Paragraph>
            <Link to={`/knowledge-bases/${kb.id}/reading`}>打开阅读空间 →</Link>
          </Card>
        ))}
      </div>
      {libraries.data?.totalElements === 0 ? (
        <Empty description="请先创建知识库并上传阅读资料">
          <Link to="/knowledge-bases">前往知识库</Link>
        </Empty>
      ) : null}
      <Pagination
        current={page + 1}
        pageSize={12}
        total={libraries.data?.totalElements ?? 0}
        showSizeChanger={false}
        hideOnSinglePage
        onChange={(value) => setPage(value - 1)}
      />
    </>
  );
}

function ReadingWorkspace({ knowledgeBaseId }: { knowledgeBaseId: number }) {
  const navigate = useNavigate();
  const client = useQueryClient();
  const [searchParams] = useSearchParams();
  const initialDoc = parsePositiveRouteId(searchParams.get('documentId') ?? undefined);
  const [creating, setCreating] = useState(Boolean(initialDoc));
  const [selected, setSelected] = useState<number[]>(initialDoc ? [initialDoc] : []);
  const [goal, setGoal] = useState('');
  const [comprehensive, setComprehensive] = useState(false);
  const [page, setPage] = useState(0);
  const lastSubmission = useRef<{ signature: string; key: string } | null>(null);
  const library = useQuery({
    queryKey: ['reading-library', knowledgeBaseId],
    queryFn: () => knowledgeBaseApi.get(knowledgeBaseId),
  });
  const tasks = useQuery({
    queryKey: ['reading-tasks', knowledgeBaseId, page],
    queryFn: () => readingApi.list(knowledgeBaseId, page),
    refetchInterval: (query) =>
      query.state.data?.content.some((t) => ['PENDING', 'RUNNING'].includes(t.status))
        ? 3000
        : false,
  });
  const create = useMutation({
    mutationFn: (input: CreateReadingTask) => {
      const signature = JSON.stringify(input);
      if (lastSubmission.current?.signature !== signature)
        lastSubmission.current = { signature, key: crypto.randomUUID() };
      return readingApi.create(input, lastSubmission.current.key);
    },
    retry: false,
    onSuccess: (task) => {
      void client.invalidateQueries({ queryKey: ['reading-tasks', knowledgeBaseId] });
      navigate(`/knowledge-bases/${knowledgeBaseId}/reading/${task.id}`);
    },
  });
  return (
    <>
      <PageHeader
        title={library.data ? `${library.data.name} · 阅读空间` : '主题阅读'}
        description="围绕一个阅读目标整理事实、观点和推论，随时查看原文依据。"
        actions={
          <Button
            type="primary"
            icon={<PlusOutlined />}
            onClick={() => setCreating((value) => !value)}
          >
            新建阅读任务
          </Button>
        }
      />
      <ReadingError error={library.error} />
      {creating || tasks.data?.totalElements === 0 ? (
        <Card className="reading-create-card" title="这次想读懂什么？">
          <Form
            layout="vertical"
            onFinish={() =>
              create.mutate({
                knowledgeBaseId,
                goal: goal.trim(),
                documentIds: [...selected].sort((a, b) => a - b),
                comprehensive,
              })
            }
          >
            <Form.Item label="选定资料" required>
              <ReadingSourcePicker
                knowledgeBaseId={knowledgeBaseId}
                selected={selected}
                onChange={setSelected}
              />
            </Form.Item>
            <Form.Item label="阅读目标" required>
              <Input.TextArea
                aria-label="阅读目标"
                value={goal}
                onChange={(event) => setGoal(event.target.value)}
                rows={3}
                maxLength={2000}
                showCount
                placeholder="例如：围绕风月宝鉴梳理情节与评点，区分原文事实和不同解读，并给出依据。"
              />
            </Form.Item>
            <Form.Item>
              <Checkbox
                checked={comprehensive}
                onChange={(event) => setComprehensive(event.target.checked)}
              >
                逐段审阅选定资料
              </Checkbox>
              <p className="reading-muted">
                全面阅读可能需要多轮。每轮先保存可靠成果，再由你决定是否继续补全。
              </p>
            </Form.Item>
            <ReadingError error={create.error} />
            <Button
              type="primary"
              htmlType="submit"
              loading={create.isPending}
              disabled={!goal.trim() || selected.length === 0}
            >
              开始阅读
            </Button>
          </Form>
        </Card>
      ) : null}
      <Card
        className="reading-task-list"
        title="阅读任务与笔记"
        extra={
          <Button type="text" onClick={() => void tasks.refetch()}>
            刷新
          </Button>
        }
      >
        <ReadingError error={tasks.error} />
        <Table<ReadingTaskSummary>
          rowKey="id"
          dataSource={tasks.data?.content ?? []}
          loading={tasks.isLoading}
          pagination={false}
          scroll={{ x: 650 }}
          columns={[
            {
              title: '阅读目标',
              dataIndex: 'goal',
              render: (text: string, row) => (
                <Link
                  className="reading-task-goal"
                  to={`/knowledge-bases/${knowledgeBaseId}/reading/${row.id}`}
                >
                  {text}
                </Link>
              ),
            },
            {
              title: '本轮状态',
              width: 130,
              render: (_, row) => <ReadingStatusTag value={row.status} />,
            },
            {
              title: '交付情况',
              width: 145,
              render: (_, row) => <CompletionTag value={row.completion} />,
            },
            { title: '更新于', width: 160, render: (_, row) => formatDateTime(row.updatedAt) },
          ]}
          locale={{ emptyText: '还没有阅读任务。选定资料，开始你的第一份主题笔记。' }}
        />
        <Space className="reading-pagination">
          <Pagination
            current={page + 1}
            pageSize={20}
            total={tasks.data?.totalElements ?? 0}
            showSizeChanger={false}
            hideOnSinglePage
            onChange={(value) => setPage(value - 1)}
          />
        </Space>
      </Card>
    </>
  );
}
