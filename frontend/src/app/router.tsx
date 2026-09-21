import { createBrowserRouter } from 'react-router-dom';
import { AppLayout } from '../components/layout/AppLayout';
import { DashboardPage } from '../features/dashboard/DashboardPage';
import { DocumentDetailPage } from '../features/document/DocumentDetailPage';
import { FuturePage } from '../features/placeholder/FuturePage';
import { KnowledgeBaseDetailPage } from '../features/knowledge-base/KnowledgeBaseDetailPage';
import { KnowledgeBaseListPage } from '../features/knowledge-base/KnowledgeBaseListPage';
import { NotFoundPage } from './not-found-page';
import { RouteErrorPage } from './route-error';

export const router = createBrowserRouter([
  {
    path: '/',
    element: <AppLayout />,
    errorElement: <RouteErrorPage />,
    children: [
      { index: true, element: <DashboardPage /> },
      { path: 'knowledge-bases', element: <KnowledgeBaseListPage /> },
      { path: 'knowledge-bases/:kbId', element: <KnowledgeBaseDetailPage /> },
      {
        path: 'knowledge-bases/:kbId/documents/:documentId',
        element: <DocumentDetailPage />,
      },
      {
        path: 'knowledge-bases/:kbId/chat',
        element: <FuturePage title="智能问答" description="新会话入口将在 FE06 接入。" />,
      },
      {
        path: 'knowledge-bases/:kbId/chat/:conversationId',
        element: <FuturePage title="智能问答" description="多轮会话将在 FE06 接入。" />,
      },
      {
        path: 'agent-executions',
        element: <FuturePage title="Agent 任务中心" description="执行任务查询将在 FE08 接入。" />,
      },
      {
        path: 'agent-executions/:executionId',
        element: <FuturePage title="Agent 执行详情" description="执行 Timeline 将在 FE08 接入。" />,
      },
      {
        path: 'system',
        element: <FuturePage title="系统状态" description="安全健康检查将在 FE12 接入。" />,
      },
      {
        path: 'evaluation',
        element: <FuturePage title="Benchmark 报告" description="评测报告将在 FE14 接入。" />,
      },
      { path: '*', element: <NotFoundPage /> },
    ],
  },
]);
