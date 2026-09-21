import { DatabaseOutlined, FileTextOutlined, RocketOutlined } from '@ant-design/icons';
import { Button, Card, Col, Row, Typography } from 'antd';
import { Link, useNavigate } from 'react-router-dom';
import { PageHeader } from '../../components/layout/PageHeader';

export function DashboardPage() {
  const navigate = useNavigate();
  return (
    <>
      <PageHeader
        title="多范式 AI Agent 文档智能处理平台"
        description="上传科研资料、跟踪处理状态，并在后续模块中进行可追溯的智能问答。"
        actions={
          <Button
            type="primary"
            icon={<DatabaseOutlined />}
            onClick={() => navigate('/knowledge-bases')}
          >
            进入知识库
          </Button>
        }
      />

      <Row gutter={[16, 16]}>
        <Col xs={24} md={8}>
          <Card className="dashboard-card" title="知识库管理" extra={<DatabaseOutlined />}>
            <Typography.Paragraph type="secondary">
              创建、编辑并组织面向项目或课题的文档集合。
            </Typography.Paragraph>
            <Link to="/knowledge-bases">管理知识库</Link>
          </Card>
        </Col>
        <Col xs={24} md={8}>
          <Card className="dashboard-card" title="文档处理" extra={<FileTextOutlined />}>
            <Typography.Paragraph type="secondary">
              支持 PDF、DOCX、TXT 上传，并可追踪每个文档的处理状态。
            </Typography.Paragraph>
            <Typography.Text type="secondary">请先选择一个知识库。</Typography.Text>
          </Card>
        </Col>
        <Col xs={24} md={8}>
          <Card className="dashboard-card" title="Agent 工作流" extra={<RocketOutlined />}>
            <Typography.Paragraph type="secondary">
              多轮问答、执行任务和系统健康监控将在后续 FE 模块逐步接入。
            </Typography.Paragraph>
            <Typography.Text type="secondary">当前可从知识库开始。</Typography.Text>
          </Card>
        </Col>
      </Row>
    </>
  );
}
