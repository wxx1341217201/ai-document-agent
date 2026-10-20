import {
  ArrowDownOutlined,
  ArrowRightOutlined,
  ArrowUpOutlined,
  DatabaseOutlined,
  FileTextOutlined,
  FolderOpenOutlined,
  MessageOutlined,
} from '@ant-design/icons';
import { Button, Card, List } from 'antd';
import { Link, useNavigate } from 'react-router-dom';
import { EmptyState, StatusTag } from '../../components/feedback';
import { formatDateTime } from '../../utils/format';
import { useDashboard } from './dashboard.hooks';
import { RecentListPanel } from './RecentListPanel';

export function DashboardPage() {
  const navigate = useNavigate();
  const { knowledgeBases, documents } = useDashboard();
  const recentKnowledgeBases = knowledgeBases.data?.pages.flatMap((page) => page.content) ?? [];
  const recentDocuments = documents.data?.pages.flatMap((page) => page.content) ?? [];
  const hasKnowledgeBases = recentKnowledgeBases.length > 0;
  const questionTarget = hasKnowledgeBases
    ? `/knowledge-bases/${recentKnowledgeBases[0].id}/chat`
    : '/knowledge-bases';
  return (
    <div className="dashboard-page">
      <section className="dashboard-hero" aria-labelledby="dashboard-heading">
        <div className="dashboard-hero-copy">
          <span className="home-eyebrow">A SPACE FOR YOUR KNOWLEDGE</span>
          <h1 id="dashboard-heading">
            让文档，
            <br />
            成为你的知识。
          </h1>
          <p>
            从资料整理到深度问答，
            <br className="dashboard-mobile-break" />
            让每一次思考，都有据可循。
          </p>
          <div className="dashboard-hero-actions">
            <Button type="primary" onClick={() => navigate('/knowledge-bases')}>
              进入知识库 <ArrowRightOutlined />
            </Button>
            <a href="#recent-work">
              继续上次探索 <ArrowDownOutlined aria-hidden="true" />
            </a>
          </div>
        </div>
        <KnowledgeIllustration />
      </section>

      <nav className="quick-entry-grid" aria-label="首页快捷入口">
        <Link className="quick-entry" to="/knowledge-bases" aria-label="整理我的资料">
          <span className="entry-icon">
            <FolderOpenOutlined aria-hidden="true" />
          </span>
          <span>
            <strong>整理我的资料</strong>
            <small>建立知识库，让文档井然有序</small>
          </span>
          <ArrowUpOutlined className="entry-arrow" rotate={45} aria-hidden="true" />
        </Link>
        <Link className="quick-entry" to={questionTarget} aria-label="与知识对话">
          <span className="entry-icon">
            <MessageOutlined aria-hidden="true" />
          </span>
          <span>
            <strong>与知识对话</strong>
            <small>基于文档提问，追溯答案来源</small>
          </span>
          <ArrowUpOutlined className="entry-arrow" rotate={45} aria-hidden="true" />
        </Link>
        <a className="quick-entry" href="#recent-documents" aria-label="回到文档原处">
          <span className="entry-icon">
            <FileTextOutlined aria-hidden="true" />
          </span>
          <span>
            <strong>回到文档原处</strong>
            <small>从最近资料，接着上次的探索</small>
          </span>
          <ArrowUpOutlined className="entry-arrow" rotate={45} aria-hidden="true" />
        </a>
      </nav>

      <section id="recent-work" className="dashboard-recent" aria-labelledby="recent-work-heading">
        <div className="dashboard-section-heading">
          <div>
            <span className="home-eyebrow">PICK UP WHERE YOU LEFT OFF</span>
            <h2 id="recent-work-heading">继续你的探索</h2>
          </div>
          <p>{hasKnowledgeBases ? '你的资料与思考，都在这里。' : '从第一份资料，开始积累知识。'}</p>
        </div>
        <div className="dashboard-grid">
          <Card
            id="recent-knowledge-bases"
            className="dashboard-card"
            title={
              <span>
                <DatabaseOutlined /> 最近知识库
              </span>
            }
            extra={<Link to="/knowledge-bases">全部知识库</Link>}
          >
            <RecentListPanel label="知识库" query={knowledgeBases}>
              <List
                dataSource={recentKnowledgeBases}
                locale={{
                  emptyText: <EmptyState description="还没有知识库，创建一个开始整理资料。" />,
                }}
                renderItem={(kb) => (
                  <List.Item>
                    <List.Item.Meta
                      avatar={
                        <span className="collection-icon">
                          <FolderOpenOutlined />
                        </span>
                      }
                      title={<Link to={'/knowledge-bases/' + kb.id}>{kb.name}</Link>}
                      description={kb.description || '更新于 ' + formatDateTime(kb.updatedAt)}
                    />
                    <Link
                      className="dashboard-ask-link"
                      to={'/knowledge-bases/' + kb.id + '/chat'}
                      aria-label={'向 ' + kb.name + ' 提问'}
                    >
                      提问
                    </Link>
                  </List.Item>
                )}
              />
            </RecentListPanel>
          </Card>
          <Card
            id="recent-documents"
            className="dashboard-card"
            title={
              <span>
                <FileTextOutlined /> 最近文档
              </span>
            }
          >
            <RecentListPanel label="文档" query={documents}>
              <List
                dataSource={recentDocuments}
                locale={{
                  emptyText: <EmptyState description="进入知识库上传第一份文档。" />,
                }}
                renderItem={(document) => (
                  <List.Item extra={<StatusTag status={document.status} />}>
                    <List.Item.Meta
                      avatar={
                        <span className="file-type-icon">{document.extension.toUpperCase()}</span>
                      }
                      title={
                        <Link
                          to={
                            '/knowledge-bases/' +
                            document.knowledgeBaseId +
                            '/documents/' +
                            document.id
                          }
                        >
                          {document.originalName}
                        </Link>
                      }
                      description={formatDateTime(document.updatedAt)}
                    />
                  </List.Item>
                )}
              />
            </RecentListPanel>
          </Card>
        </div>
      </section>
      <footer className="dashboard-footer">
        <span>DOCUMENT AGENT</span>
        <span>让知识自然发生。</span>
      </footer>
    </div>
  );
}

function KnowledgeIllustration() {
  return (
    <div className="knowledge-art" aria-hidden="true">
      <div className="atmospheric-orb" />
      <div className="art-orbit art-orbit-outer" />
      <div className="art-orbit art-orbit-inner" />
      <div className="art-document art-document-back" />
      <div className="art-document art-document-front">
        <span className="art-document-label">DOCUMENT</span>
        <FileTextOutlined />
        <span className="art-line art-line-title" />
        <span className="art-line" />
        <span className="art-line" />
        <span className="art-line art-line-short" />
        <span className="art-document-footer">A new perspective.</span>
      </div>
      <span className="art-spark art-spark-one">✳</span>
      <span className="art-spark art-spark-two">＋</span>
      <span className="art-caption">FROM DOCUMENTS TO DISCOVERY</span>
    </div>
  );
}
