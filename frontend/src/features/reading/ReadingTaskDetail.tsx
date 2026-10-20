import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import {
  Alert,
  Button,
  Card,
  Collapse,
  Input,
  Modal,
  Pagination,
  Progress,
  Space,
  Table,
  Tag,
} from 'antd';
import { useEffect, useRef, useState } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import {
  readingApi,
  type CreateReadingTask,
  type ReadingSource,
  type ReadingTaskSummary,
} from '../../api/reading.api';
import { PageError, PageLoading } from '../../components/feedback';
import { PageHeader } from '../../components/layout/PageHeader';
import { formatDateTime } from '../../utils/format';
import { ReadingNoteEditor } from './ReadingNoteEditor';
import {
  CompletionTag,
  ReadingError,
  ReadingMarkdown,
  ReadingSourceDrawer,
  ReadingSourcePicker,
  ReadingStatusTag,
} from './reading-components';
import { completionLabels } from './reading-labels';

const engineLabels: Record<string, string> = {
  RETRIEVAL_FIRST: '轻量检索',
  REACT: '工具交互',
  PLAN_EXECUTE_REFLECT: '规划与核验',
};
export function ReadingTaskDetail({
  knowledgeBaseId,
  taskId,
}: {
  knowledgeBaseId: number;
  taskId: string;
}) {
  const client = useQueryClient();
  const navigate = useNavigate();
  const [source, setSource] = useState<ReadingSource>();
  const [selectedResult, setSelectedResult] = useState<string>();
  const [resultPage, setResultPage] = useState(0);
  const [historyOpen, setHistoryOpen] = useState(false);
  const [relatedPage, setRelatedPage] = useState(0);
  const [followOpen, setFollowOpen] = useState(false);
  const [followGoal, setFollowGoal] = useState('');
  const [expandOpen, setExpandOpen] = useState(false);
  const [expandedIds, setExpandedIds] = useState<number[]>([]);
  const followKey = useRef<{ signature: string; key: string } | null>(null);
  const actionLock = useRef(false);
  const taskKey = ['reading-task', knowledgeBaseId, taskId];
  const task = useQuery({
    queryKey: taskKey,
    queryFn: () => readingApi.get(knowledgeBaseId, taskId),
    refetchInterval: (query) =>
      ['RUNNING', 'PENDING'].includes(query.state.data?.status ?? '') ? 1500 : false,
  });
  const resultId = selectedResult ?? task.data?.latestResultId;
  const result = useQuery({
    queryKey: ['reading-result', knowledgeBaseId, taskId, resultId],
    queryFn: () => readingApi.result(knowledgeBaseId, taskId, resultId!),
    enabled: Boolean(resultId),
    staleTime: Infinity,
  });
  const latestId = task.data?.latestResultId;
  const latest = useQuery({
    queryKey: ['reading-result', knowledgeBaseId, taskId, latestId],
    queryFn: () => readingApi.result(knowledgeBaseId, taskId, latestId!),
    enabled: Boolean(latestId),
    staleTime: Infinity,
  });
  const note = useQuery({
    queryKey: ['reading-note', knowledgeBaseId, taskId],
    queryFn: () => readingApi.note(knowledgeBaseId, taskId),
    enabled: task.isSuccess,
  });
  useEffect(() => {
    if (latestId)
      void client.invalidateQueries({ queryKey: ['reading-note', knowledgeBaseId, taskId] });
  }, [client, knowledgeBaseId, taskId, latestId]);
  const history = useQuery({
    queryKey: ['reading-result-history', knowledgeBaseId, taskId, resultPage, latestId],
    queryFn: () => readingApi.results(knowledgeBaseId, taskId, resultPage),
    enabled: historyOpen,
  });
  const related = useQuery({
    queryKey: ['reading-related', knowledgeBaseId, task.data?.sessionId, relatedPage],
    queryFn: () => readingApi.list(knowledgeBaseId, relatedPage, 20, task.data?.sessionId),
    enabled: Boolean(task.data?.sessionId),
  });
  function reconcile() {
    void client.invalidateQueries({ queryKey: taskKey });
    void client.invalidateQueries({ queryKey: ['reading-tasks', knowledgeBaseId] });
    void client.invalidateQueries({ queryKey: ['reading-note', knowledgeBaseId, taskId] });
  }
  const action = useMutation({
    mutationFn: (name: 'continue' | 'resume' | 'cancel' | 'refresh-sources') =>
      readingApi.action(knowledgeBaseId, task.data!, name),
    retry: false,
    onSuccess: (updated) => {
      client.setQueryData(taskKey, updated);
      reconcile();
    },
    onError: reconcile,
  });
  function changeTask(name: 'continue' | 'resume' | 'cancel' | 'refresh-sources') {
    if (actionLock.current) return;
    actionLock.current = true;
    action.mutate(name, {
      onSettled: () => {
        actionLock.current = false;
      },
    });
  }
  const expand = useMutation({
    mutationFn: () => readingApi.expand(knowledgeBaseId, task.data!, expandedIds),
    retry: false,
    onSuccess: (updated) => {
      client.setQueryData(taskKey, updated);
      setExpandOpen(false);
      reconcile();
    },
    onError: reconcile,
  });
  const follow = useMutation({
    mutationFn: (input: CreateReadingTask) => {
      const signature = JSON.stringify(input);
      if (followKey.current?.signature !== signature)
        followKey.current = { signature, key: crypto.randomUUID() };
      return readingApi.create(input, followKey.current.key);
    },
    retry: false,
    onSuccess: (created) => {
      setFollowOpen(false);
      void client.invalidateQueries({ queryKey: ['reading-related', knowledgeBaseId] });
      navigate(`/knowledge-bases/${knowledgeBaseId}/reading/${created.id}`);
    },
  });
  if (task.isLoading) return <PageLoading label="正在打开阅读任务…" />;
  if (task.isError && !task.data)
    return <PageError error={task.error} onRetry={() => void task.refetch()} />;
  const data = task.data;
  if (!data) return null;
  const active = ['PENDING', 'RUNNING'].includes(data.status);
  return (
    <>
      <PageHeader
        title="主题阅读"
        description={data.goal}
        actions={
          <>
            <Button onClick={() => navigate(`/knowledge-bases/${knowledgeBaseId}/reading`)}>
              返回阅读空间
            </Button>
            <Button
              disabled={active}
              onClick={() => {
                setExpandedIds(data.documents.map((d) => d.id));
                setExpandOpen(true);
              }}
            >
              补充资料
            </Button>
            <Button onClick={() => setFollowOpen(true)}>提出新问题</Button>
          </>
        }
      />
      <ReadingError error={action.error} />
      <ReadingError error={task.error} />
      {data.errorMessage ? <Alert type="warning" showIcon message={data.errorMessage} /> : null}
      <Card className="reading-run-card">
        <div className="reading-run-summary">
          <Space wrap>
            <ReadingStatusTag value={data.status} />
            <CompletionTag value={data.completion} />
            <span>第 {data.roundNo} 轮</span>
          </Space>
          <Space wrap>
            {active ? (
              <Button loading={action.isPending} onClick={() => changeTask('cancel')}>
                取消本轮
              </Button>
            ) : null}
            {data.status === 'INTERRUPTED' ? (
              <Button
                type="primary"
                loading={action.isPending}
                onClick={() => changeTask('resume')}
              >
                恢复本轮
              </Button>
            ) : null}
            {!active && data.completion !== 'FULL' ? (
              <Button
                type={data.status === 'INTERRUPTED' ? 'default' : 'primary'}
                loading={action.isPending}
                onClick={() => changeTask('continue')}
              >
                继续补全
              </Button>
            ) : null}
            {!active && note.data?.sourceStatus === 'CHANGED' ? (
              <Button loading={action.isPending} onClick={() => changeTask('refresh-sources')}>
                基于更新资料重新分析
              </Button>
            ) : null}
          </Space>
        </div>
        <p className="reading-muted">
          {active
            ? '任务在后台执行，可以离开页面，稍后回来查看。每轮最多 3 分钟。'
            : '恢复本轮保留原有额度；继续补全会开启新一轮，复用仍有效的成果。'}
        </p>
        <div className="reading-budget">
          <span>模型请求 {data.usage.modelCalls} / 8</span>
          <span>工具调用 {data.usage.toolCalls} / 12</span>
          <span>重规划 {data.usage.replans} / 1</span>
          <span>
            本轮用量 {(data.usage.promptTokens + data.usage.completionTokens).toLocaleString()}{' '}
            Token
          </span>
        </div>
        <div className="reading-tags">
          {data.documents.map((d) => (
            <Tag key={d.id}>{d.name}</Tag>
          ))}
        </div>
        {data.documents.some((d) => d.limitations?.length) ? (
          <p className="reading-muted">
            当前分析基于抽取文本，图像、图表、复杂公式及扫描识别的完整性仍需核对。
          </p>
        ) : null}
        <Collapse
          ghost
          items={[
            {
              key: 'details',
              label: '目标、覆盖与执行记录',
              children: (
                <>
                  <ul className="reading-goals">
                    {data.goals.map((goal) => (
                      <li key={goal.id}>
                        <Tag>{goal.covered ? '已满足' : '待核验'}</Tag>
                        {goal.description}
                      </li>
                    ))}
                  </ul>
                  {data.comprehensive ? (
                    data.coverage.map((c) => (
                      <div className="reading-coverage" key={c.documentId}>
                        <span>{c.name}</span>
                        <Progress
                          percent={
                            c.totalChunks ? Math.round((c.reviewedChunks / c.totalChunks) * 100) : 0
                          }
                          strokeColor="var(--color-primary)"
                          format={() => `${c.reviewedChunks} / ${c.totalChunks} 片段`}
                        />
                      </div>
                    ))
                  ) : (
                    <p className="reading-muted">
                      当前采用专题取证；所引片段支持分析，不表示已逐段审阅全文。
                    </p>
                  )}
                  <ol className="reading-stages">
                    {data.stages.map((s, index) => (
                      <li key={`${index}-${s.at}`}>
                        <span>{engineLabels[s.engine] ?? '执行阶段'}</span>
                        <span>{s.message}</span>
                        <time>{formatDateTime(s.at)}</time>
                      </li>
                    ))}
                  </ol>
                </>
              ),
            },
          ]}
        />
      </Card>
      <div className="reading-detail-grid">
        <Card
          title={selectedResult ? '历史分析结果' : '阅读结果'}
          extra={
            <Button type="text" onClick={() => setHistoryOpen(true)}>
              结果版本
            </Button>
          }
        >
          <ReadingError error={result.error} />
          {result.isLoading && resultId ? <PageLoading label="正在加载分析结果…" /> : null}
          {result.data ? (
            <>
              <Space wrap className="reading-result-meta">
                <CompletionTag value={result.data.completion} />
                <span>
                  第 {result.data.roundNo} 轮 · {formatDateTime(result.data.createdAt)}
                </span>
                {selectedResult ? (
                  <Button type="link" onClick={() => setSelectedResult(undefined)}>
                    返回最新结果
                  </Button>
                ) : null}
              </Space>
              <ReadingMarkdown
                content={result.data.markdown}
                sources={result.data.sources}
                onSource={setSource}
              />
            </>
          ) : !resultId ? (
            <p className="reading-muted">取得并核验原文后，分析结果会保存在这里。</p>
          ) : null}
        </Card>
        <Card title="我的阅读笔记">
          <ReadingError error={note.error || latest.error} />
          {note.isLoading ? <PageLoading label="正在加载笔记…" /> : null}
          {note.data ? (
            <ReadingNoteEditor
              knowledgeBaseId={knowledgeBaseId}
              taskId={taskId}
              note={note.data}
              aiDraft={latest.data}
              onSource={setSource}
            />
          ) : null}
        </Card>
      </div>
      {data.parentTaskId ? (
        <p>
          <Link to={`/knowledge-bases/${knowledgeBaseId}/reading/${data.parentTaskId}`}>
            查看这次追问关联的原任务
          </Link>
        </p>
      ) : null}
      {(related.data?.totalElements ?? 0) > 1 ? (
        <Card title="同一阅读会话中的问题" className="reading-related">
          <Table<ReadingTaskSummary>
            rowKey="id"
            pagination={false}
            dataSource={related.data?.content.filter(
              (t) => t.sessionId === data.sessionId && t.id !== data.id,
            )}
            columns={[
              {
                title: '阅读目标',
                render: (_, row) => (
                  <Link to={`/knowledge-bases/${knowledgeBaseId}/reading/${row.id}`}>
                    {row.goal}
                  </Link>
                ),
              },
              {
                title: '交付情况',
                width: 150,
                render: (_, row) => <CompletionTag value={row.completion} />,
              },
            ]}
          />
          <Pagination
            current={relatedPage + 1}
            pageSize={20}
            total={related.data?.totalElements ?? 0}
            hideOnSinglePage
            showSizeChanger={false}
            onChange={(value) => setRelatedPage(value - 1)}
          />
        </Card>
      ) : null}
      <Modal
        title="围绕这些资料继续提问"
        open={followOpen}
        onCancel={() => setFollowOpen(false)}
        okText="创建关联阅读任务"
        confirmLoading={follow.isPending}
        okButtonProps={{ disabled: !followGoal.trim() }}
        onOk={() =>
          follow.mutate({
            knowledgeBaseId,
            goal: followGoal.trim(),
            documentIds: data.documents.map((d) => d.id),
            comprehensive: false,
            parentTaskId: taskId,
          })
        }
      >
        <p>新问题沿用当前选定的资料范围，原任务的目标和结果会保留。</p>
        <Input.TextArea
          aria-label="追问内容"
          value={followGoal}
          onChange={(event) => setFollowGoal(event.target.value)}
          rows={4}
          maxLength={2000}
        />
        <ReadingError error={follow.error} />
      </Modal>
      <Modal
        title="补充本次阅读资料"
        open={expandOpen}
        width={760}
        onCancel={() => setExpandOpen(false)}
        okText="确认范围并开启新一轮"
        confirmLoading={expand.isPending}
        okButtonProps={{ disabled: expandedIds.length <= data.documents.length }}
        onOk={() => expand.mutate()}
      >
        <p>保留已有资料和阅读目标，加入补充资料后重新核验分析，历史笔记与结果版本仍可查看。</p>
        <ReadingSourcePicker
          knowledgeBaseId={knowledgeBaseId}
          selected={expandedIds}
          onChange={setExpandedIds}
          locked={data.documents.map((d) => d.id)}
        />
        <ReadingError error={expand.error} />
      </Modal>
      <Modal
        title="已交付的结果版本"
        open={historyOpen}
        onCancel={() => setHistoryOpen(false)}
        footer={null}
      >
        <ReadingError error={history.error} />
        <Space direction="vertical">
          {history.data?.content.map((r) => (
            <Button
              key={r.id}
              onClick={() => {
                setSelectedResult(r.id);
                setHistoryOpen(false);
              }}
            >
              第 {r.roundNo} 轮 · {completionLabels[r.completion]} · {formatDateTime(r.createdAt)}
            </Button>
          ))}
        </Space>
        <Pagination
          current={resultPage + 1}
          pageSize={20}
          total={history.data?.totalElements ?? 0}
          showSizeChanger={false}
          hideOnSinglePage
          onChange={(value) => setResultPage(value - 1)}
        />
      </Modal>
      <ReadingSourceDrawer
        source={source}
        knowledgeBaseId={knowledgeBaseId}
        onClose={() => setSource(undefined)}
      />
    </>
  );
}
