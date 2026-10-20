import { ApiOutlined, ReloadOutlined } from '@ant-design/icons';
import { useQuery } from '@tanstack/react-query';
import { Button, Card, Descriptions, Space, Typography } from 'antd';
import { systemApi } from '../../api/system.api';
import { queryKeys } from '../../api/query-keys';
import { EmptyState, PageLoading, RequestErrorAlert, StatusTag } from '../../components/feedback';
import type { SystemHealth } from '../../api/contracts';
import { formatDateTime } from '../../utils/format';

const labels = {
  database: 'MySQL',
  redis: 'Redis',
  rabbitmq: 'RabbitMQ',
  qdrant: 'Qdrant',
  elasticsearch: 'Elasticsearch',
} as const;

export function SystemHealthCard() {
  const query = useQuery({
    queryKey: queryKeys.system.health,
    queryFn: ({ signal }) => systemApi.health(signal),
    staleTime: 30_000,
    refetchInterval: (current) => (current.state.data ? 30_000 : false),
  });
  return (
    <Card
      className="system-health-card"
      title={
        <span>
          <ApiOutlined /> 系统健康
        </span>
      }
      extra={
        <Button
          type="text"
          size="small"
          icon={<ReloadOutlined />}
          loading={query.isFetching}
          onClick={() => void query.refetch()}
        >
          刷新健康状态
        </Button>
      }
    >
      {query.isLoading ? <PageLoading label="正在检查系统状态…" /> : null}
      {query.isError ? (
        <>
          <RequestErrorAlert error={query.error} />
          <Typography.Paragraph type="secondary">
            健康状态暂时无法获取，请稍后重试。
          </Typography.Paragraph>
        </>
      ) : null}
      {query.data ? (
        <>
          <Space wrap>
            <Typography.Text strong>整体状态</Typography.Text>
            <StatusTag status={query.data.status} />
          </Space>
          {Object.keys(query.data.components).length === 0 ? (
            <EmptyState description="服务端未返回组件状态。" />
          ) : (
            <Descriptions column={1} className="system-health-components">
              {(
                Object.entries(query.data.components) as [
                  keyof SystemHealth['components'],
                  string,
                ][]
              ).map(([name, status]) => (
                <Descriptions.Item key={name} label={labels[name]}>
                  <StatusTag status={status} />
                </Descriptions.Item>
              ))}
            </Descriptions>
          )}
          <Typography.Paragraph type="secondary">
            {query.isError ? '上次成功检查：' : '检查于：'}
            {formatDateTime(new Date(query.dataUpdatedAt).toISOString())}
          </Typography.Paragraph>
        </>
      ) : !query.isLoading && !query.isError ? (
        <EmptyState description="暂无系统健康数据。" />
      ) : null}
    </Card>
  );
}
