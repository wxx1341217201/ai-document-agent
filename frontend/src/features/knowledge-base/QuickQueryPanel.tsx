import { SendOutlined } from '@ant-design/icons';
import {
  Alert,
  Button,
  Card,
  Collapse,
  Form,
  Input,
  InputNumber,
  Space,
  Spin,
  Switch,
  Typography,
} from 'antd';
import { useState } from 'react';
import type { RagCitation } from '../../api/contracts';
import { EmptyState, RequestErrorAlert } from '../../components/feedback';
import { CitationDrawer } from './CitationDrawer';
import { RagAnswerMarkdown } from './RagAnswerMarkdown';
import { useRagQuery } from './rag.hooks';

const DEFAULT_TOP_K = 8;

interface QueryFormValues {
  question: string;
  topK: number;
  rerank: boolean;
}

export function QuickQueryPanel({ knowledgeBaseId }: { knowledgeBaseId: number }) {
  const [form] = Form.useForm<QueryFormValues>();
  const [selectedCitation, setSelectedCitation] = useState<RagCitation>();
  const queryMutation = useRagQuery(knowledgeBaseId);
  const response = queryMutation.data;

  function submitQuery(values: QueryFormValues) {
    setSelectedCitation(undefined);
    queryMutation.mutate({
      question: values.question.trim(),
      // Collapsed Ant Design fields are not registered until opened, so retain
      // the UI defaults in the request rather than silently omitting them.
      topK: values.topK ?? DEFAULT_TOP_K,
      rerank: values.rerank ?? true,
    });
  }

  return (
    <>
      <Card
        className="rag-query-panel"
        title="快速问答"
        extra={<Typography.Text type="secondary">基于当前知识库已索引的资料</Typography.Text>}
      >
        <Form<QueryFormValues>
          form={form}
          layout="vertical"
          initialValues={{ topK: DEFAULT_TOP_K, rerank: true }}
          onFinish={submitQuery}
          requiredMark={false}
        >
          <Form.Item
            label="问题"
            name="question"
            rules={[
              { required: true, whitespace: true, message: '请输入问题后再开始问答。' },
              { max: 2_000, message: '问题不能超过 2000 个字符。' },
            ]}
          >
            <Input.TextArea
              aria-label="问题"
              autoSize={{ minRows: 3, maxRows: 8 }}
              maxLength={2_000}
              placeholder="例如：系统如何处理重复消息？"
              disabled={queryMutation.isPending}
            />
          </Form.Item>

          <Collapse
            className="rag-query-settings"
            size="small"
            items={[
              {
                key: 'advanced',
                label: '高级设置',
                children: (
                  <Space wrap size="large">
                    <Form.Item
                      label="Top K"
                      name="topK"
                      rules={[
                        { required: true, message: '请选择 Top K。' },
                        { type: 'number', min: 1, max: 20, message: 'Top K 必须在 1 到 20 之间。' },
                      ]}
                    >
                      <InputNumber
                        min={1}
                        max={20}
                        precision={0}
                        aria-label="Top K"
                        disabled={queryMutation.isPending}
                      />
                    </Form.Item>
                    <Form.Item label="启用重排" name="rerank" valuePropName="checked">
                      <Switch
                        checkedChildren="开"
                        unCheckedChildren="关"
                        aria-label="启用重排"
                        disabled={queryMutation.isPending}
                      />
                    </Form.Item>
                  </Space>
                ),
              },
            ]}
          />

          <Space className="rag-query-actions">
            <Button
              type="primary"
              htmlType="submit"
              icon={<SendOutlined />}
              loading={queryMutation.isPending}
            >
              开始问答
            </Button>
            <Typography.Text type="secondary">
              仅发起非流式查询；引用与页码完全来自服务端响应。
            </Typography.Text>
          </Space>
        </Form>
      </Card>

      {queryMutation.isIdle ? (
        <div className="rag-query-state">
          <EmptyState
            title="输入问题开始快速问答"
            description="回答会附带服务端返回的可验证引用；不会由浏览器猜测文档、Chunk 或页码。"
          />
        </div>
      ) : null}

      {queryMutation.isPending ? (
        <Card className="rag-query-result">
          <div className="rag-answer-loading" role="status" aria-live="polite">
            <Spin />
            <Typography.Text>正在检索资料并生成回答…</Typography.Text>
          </div>
        </Card>
      ) : null}

      {queryMutation.isError ? (
        <Card className="rag-query-result">
          <RequestErrorAlert error={queryMutation.error} />
        </Card>
      ) : null}

      {queryMutation.isSuccess && response ? (
        <Card
          className="rag-query-result"
          title="回答"
          extra={
            <Typography.Text type="secondary">
              后端返回 {response.retrieval.candidateCount} 个候选片段
            </Typography.Text>
          }
        >
          {response.retrieval.degraded ? (
            <Alert
              className="rag-degraded-warning"
              type="warning"
              showIcon
              message="检索结果已降级"
              description="部分检索能力不可用或已回退；请结合下方引用谨慎核验回答。"
            />
          ) : null}

          {response.answer.trim() ? (
            <RagAnswerMarkdown
              answer={response.answer}
              citations={response.citations}
              onCitationClick={setSelectedCitation}
            />
          ) : (
            <EmptyState
              title="服务未返回可展示的回答"
              description="本次请求已完成，但响应中没有 answer 内容。"
            />
          )}

          <CitationList citations={response.citations} onCitationClick={setSelectedCitation} />
        </Card>
      ) : null}

      {queryMutation.isSuccess && !response ? (
        <div className="rag-query-state">
          <EmptyState
            title="服务未返回问答结果"
            description="请重新发起请求；浏览器不会自行构造回答或引用。"
          />
        </div>
      ) : null}

      <CitationDrawer
        knowledgeBaseId={knowledgeBaseId}
        citation={selectedCitation}
        onClose={() => setSelectedCitation(undefined)}
      />
    </>
  );
}

function CitationList({
  citations,
  onCitationClick,
}: {
  citations: RagCitation[];
  onCitationClick: (citation: RagCitation) => void;
}) {
  return (
    <section className="rag-citations" aria-labelledby="rag-citations-title">
      <Typography.Title id="rag-citations-title" level={5}>
        引用
      </Typography.Title>
      {citations.length > 0 ? (
        <Space wrap>
          {citations.map((citation) => (
            <Button
              key={citation.citationId + '-' + citation.chunkId}
              className="rag-citation-button"
              type="dashed"
              onClick={() => onCitationClick(citation)}
            >
              {'[' + citation.citationId + '] ' + citation.documentName}
            </Button>
          ))}
        </Space>
      ) : (
        <EmptyState
          title="本次回答未返回可验证引用"
          description="引用仅在后端响应包含 citations 时显示，前端不会根据回答内容生成引用。"
        />
      )}
    </section>
  );
}
