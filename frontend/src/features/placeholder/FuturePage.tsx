import { InfoCircleOutlined } from '@ant-design/icons';
import { Alert } from 'antd';
import { PageHeader } from '../../components/layout/PageHeader';

export function FuturePage({ title, description }: { title: string; description: string }) {
  return (
    <>
      <PageHeader title={title} description={description} />
      <Alert
        showIcon
        icon={<InfoCircleOutlined />}
        type="info"
        message="该能力将在后续前端模块接入"
        description="当前路由已可安全访问，知识库与文档处理功能可在左侧导航中使用。"
      />
    </>
  );
}
