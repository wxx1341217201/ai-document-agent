import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import {
  Alert,
  Button,
  Checkbox,
  Drawer,
  Input,
  Modal,
  Pagination,
  Space,
  Tabs,
  Typography,
} from 'antd';
import { useCallback, useState } from 'react';
import { useBeforeUnload, useBlocker } from 'react-router-dom';
import { normalizeApiError } from '../../api/client';
import {
  readingApi,
  type ReadingNote,
  type ReadingResult,
  type ReadingSource,
} from '../../api/reading.api';
import { formatDateTime } from '../../utils/format';
import { ReadingError, ReadingMarkdown } from './reading-components';

interface Buffer {
  content: string;
  baseVersion: number;
  sourceResultId?: string;
  sources?: ReadingSource[];
}
export function ReadingNoteEditor({
  knowledgeBaseId,
  taskId,
  note,
  aiDraft,
  onSource,
}: {
  knowledgeBaseId: number;
  taskId: string;
  note: ReadingNote;
  aiDraft?: ReadingResult;
  onSource: (s: ReadingSource) => void;
}) {
  const client = useQueryClient();
  const [buffer, setBuffer] = useState<Buffer | null>(null);
  const [preview, setPreview] = useState(false);
  const [selected, setSelected] = useState<number[]>([]);
  const [historyOpen, setHistoryOpen] = useState(false);
  const [historyPage, setHistoryPage] = useState(0);
  const [revisionId, setRevisionId] = useState<string>();
  const [mergeOpen, setMergeOpen] = useState(false);
  const [draftCompareOpen, setDraftCompareOpen] = useState(false);
  const content = buffer?.content ?? note.content;
  const dirty =
    buffer !== null && (content !== note.content || buffer.sourceResultId !== undefined);
  const blocker = useBlocker(dirty);
  useBeforeUnload(
    useCallback(
      (event) => {
        if (dirty) {
          event.preventDefault();
          event.returnValue = '';
        }
      },
      [dirty],
    ),
  );
  const save = useMutation({
    mutationFn: (submitted: Buffer) =>
      readingApi.edit(
        knowledgeBaseId,
        taskId,
        submitted.content,
        submitted.baseVersion,
        submitted.sourceResultId,
      ),
    retry: false,
    onSuccess: (saved, submitted) => {
      client.setQueriesData({ queryKey: ['reading-note', knowledgeBaseId, taskId] }, saved);
      setBuffer((current) =>
        !current ||
        (current.content === submitted.content &&
          current.sourceResultId === submitted.sourceResultId)
          ? null
          : { ...current, baseVersion: saved.version },
      );
      void client.invalidateQueries({ queryKey: ['reading-note', knowledgeBaseId, taskId] });
    },
    onError: () => {
      void client.invalidateQueries({ queryKey: ['reading-note', knowledgeBaseId, taskId] });
    },
  });
  const history = useQuery({
    queryKey: ['reading-note-history', knowledgeBaseId, taskId, historyPage, note.version],
    queryFn: () => readingApi.revisions(knowledgeBaseId, taskId, historyPage),
    enabled: historyOpen,
  });
  const revision = useQuery({
    queryKey: ['reading-note-revision', knowledgeBaseId, taskId, revisionId],
    queryFn: () => readingApi.revision(knowledgeBaseId, taskId, revisionId!),
    enabled: Boolean(revisionId),
  });
  const blocks = aiDraft?.markdown.split(/\n\n+/).filter(Boolean) ?? [];
  const previousDraftPending = Boolean(
    buffer?.sourceResultId && buffer.sourceResultId !== aiDraft?.id,
  );
  const previewSources = buffer?.sources ?? note.sources;
  function change(value: string, sourceResultId?: string) {
    setBuffer({
      content: value,
      baseVersion: buffer?.baseVersion ?? note.version,
      sourceResultId: sourceResultId ?? buffer?.sourceResultId,
      sources:
        sourceResultId && sourceResultId === aiDraft?.id
          ? [
              ...new Map(
                [...(buffer?.sources ?? note.sources), ...aiDraft.sources].map((s) => [
                  s.citationId,
                  s,
                ]),
              ).values(),
            ]
          : (buffer?.sources ?? note.sources),
    });
  }
  const conflict = save.error && normalizeApiError(save.error).httpStatus === 409;
  return (
    <div className="reading-note-editor">
      <div className="reading-editor-toolbar">
        <Space wrap>
          <Button
            type="primary"
            disabled={!dirty}
            loading={save.isPending}
            onClick={() =>
              save.mutate({
                content,
                baseVersion: buffer?.baseVersion ?? note.version,
                sourceResultId: buffer?.sourceResultId,
                sources: previewSources,
              })
            }
          >
            保存修改
          </Button>
          <Button onClick={() => setPreview((value) => !value)}>
            {preview ? '继续编辑' : '预览笔记'}
          </Button>
          <Button onClick={() => setHistoryOpen(true)}>历史修订</Button>
          <Button href={readingApi.exportUrl(knowledgeBaseId, taskId)} disabled={!note.content}>
            导出 Markdown
          </Button>
        </Space>
        <span className="reading-muted">
          {dirty ? '有尚未保存的修改' : `已保存 · 修订 ${note.version}`}
        </span>
      </div>
      {note.sourceStatus === 'CHANGED' || note.sourceStatus === 'UNAVAILABLE' ? (
        <Alert
          type="info"
          showIcon
          message={
            note.sourceStatus === 'CHANGED'
              ? '资料已更新，当前笔记保留原版本依据。'
              : '当前资料暂不可访问，已保存的引用快照仍可查看。'
          }
        />
      ) : null}
      <ReadingError error={save.error} />
      {conflict ? (
        <Alert
          type="warning"
          message="服务器笔记已变化，你的本地修改仍保留。请对照后合并。"
          action={
            <Button size="small" onClick={() => setMergeOpen(true)}>
              对照并合并
            </Button>
          }
        />
      ) : null}
      <Tabs
        items={[
          {
            key: 'mine',
            label: '我的笔记',
            children: preview ? (
              <ReadingMarkdown
                content={content || '尚未写入笔记。'}
                sources={previewSources}
                onSource={onSource}
              />
            ) : (
              <Input.TextArea
                aria-label="笔记内容"
                className="reading-note-input"
                value={content}
                rows={22}
                maxLength={120000}
                onChange={(event) => change(event.target.value)}
                placeholder="分析结果生成后会保存在这里，你也可以加入自己的思考。"
              />
            ),
          },
          {
            key: 'draft',
            label: note.pendingResultId ? 'AI 新版草稿 · 待采纳' : 'AI 分析草稿',
            children: aiDraft ? (
              <>
                <p className="reading-muted">
                  勾选需要的段落加入笔记，或将整份草稿载入编辑区。保存前可以继续修改。
                </p>
                {previousDraftPending ? (
                  <Alert type="info" message="编辑区包含上一版草稿，请先保存，再采纳新一版内容。" />
                ) : null}
                <Space wrap className="reading-draft-actions">
                  <Button onClick={() => setDraftCompareOpen(true)}>对照当前笔记</Button>
                  <Button
                    disabled={selected.length === 0 || previousDraftPending}
                    onClick={() => {
                      change(
                        [content, ...selected.filter((i) => blocks[i]).map((i) => blocks[i])]
                          .filter(Boolean)
                          .join('\n\n'),
                        aiDraft.id,
                      );
                      setSelected([]);
                    }}
                  >
                    将所选段落加入笔记
                  </Button>
                  <Button
                    disabled={previousDraftPending}
                    onClick={() =>
                      Modal.confirm({
                        title: '将整份草稿载入编辑区？',
                        content: '当前已保存笔记及历史修订会保留；载入后需点击保存。',
                        okText: '载入草稿',
                        cancelText: '继续对照',
                        onOk: () => change(aiDraft.markdown, aiDraft.id),
                      })
                    }
                  >
                    载入整份草稿
                  </Button>
                </Space>
                <Checkbox.Group
                  value={selected}
                  onChange={(values) => setSelected(values.map(Number))}
                  className="reading-draft-blocks"
                >
                  {blocks.map((block, index) => (
                    <div className="reading-draft-block" key={`${aiDraft.id}-${index}`}>
                      <Checkbox value={index} aria-label={`选择草稿第 ${index + 1} 段`} />
                      <pre>{block}</pre>
                    </div>
                  ))}
                </Checkbox.Group>
              </>
            ) : (
              <p className="reading-muted">分析完成后，草稿会出现在这里。</p>
            ),
          },
        ]}
      />
      <Modal
        title="合并笔记修改"
        open={mergeOpen}
        onCancel={() => setMergeOpen(false)}
        okText="保留本地内容并基于最新版本编辑"
        onOk={() => {
          setBuffer({
            content,
            baseVersion: note.version,
            sourceResultId: buffer?.sourceResultId,
            sources: previewSources,
          });
          setMergeOpen(false);
          save.reset();
        }}
      >
        <Typography.Paragraph>
          请对照服务器版本与本地修改。确认后仍需点击“保存修改”。
        </Typography.Paragraph>
        <h4>服务器当前版本</h4>
        <pre className="reading-version-text">{note.content}</pre>
        <h4>本地修改</h4>
        <pre className="reading-version-text">{content}</pre>
      </Modal>
      <Modal
        title="当前笔记与 AI 草稿对照"
        open={draftCompareOpen}
        footer={null}
        onCancel={() => setDraftCompareOpen(false)}
        width={960}
      >
        <p>AI 新结果不会自动改写当前笔记。关闭对照后可以勾选需要采纳的段落。</p>
        <div className="reading-comparison">
          <section>
            <h4>当前编辑中的笔记</h4>
            <pre className="reading-version-text">{content || '空白笔记'}</pre>
          </section>
          <section>
            <h4>AI 新版草稿（待采纳）</h4>
            <pre className="reading-version-text">{aiDraft?.markdown}</pre>
          </section>
        </div>
      </Modal>
      <Modal
        title="笔记还有未保存的修改"
        open={blocker.state === 'blocked'}
        okText="离开并放弃未保存修改"
        cancelText="继续编辑"
        onOk={() => blocker.proceed?.()}
        onCancel={() => blocker.reset?.()}
      >
        已保存的笔记仍会保留。留在页面可以先保存本地修改。
      </Modal>
      <Drawer
        title="笔记历史修订"
        width={680}
        open={historyOpen}
        onClose={() => {
          setHistoryOpen(false);
          setRevisionId(undefined);
        }}
      >
        <ReadingError error={history.error || revision.error} />
        <Space wrap>
          {history.data?.content.map((r) => (
            <Button key={r.id} onClick={() => setRevisionId(r.id)}>
              修订 {r.revision} · {formatDateTime(r.createdAt)}
            </Button>
          ))}
        </Space>
        {history.data?.totalElements === 0 ? <p>还没有历史修订。</p> : null}
        <Pagination
          current={historyPage + 1}
          pageSize={20}
          total={history.data?.totalElements ?? 0}
          showSizeChanger={false}
          hideOnSinglePage
          onChange={(value) => setHistoryPage(value - 1)}
        />
        {revision.data ? (
          <ReadingMarkdown
            content={revision.data.content || '此版本为空白笔记。'}
            sources={revision.data.sources}
            onSource={onSource}
          />
        ) : null}
      </Drawer>
    </div>
  );
}
