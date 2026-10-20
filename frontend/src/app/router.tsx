import { createBrowserRouter } from 'react-router-dom';
import { AppLayout } from '../components/layout/AppLayout';
import { DashboardPage } from '../features/dashboard/DashboardPage';
import { DocumentDetailPage } from '../features/document/DocumentDetailPage';
import { ChatPage } from '../features/chat/ChatPage';
import { ExecutionListPage } from '../features/execution/ExecutionListPage';
import { ExecutionDetailPage } from '../features/execution/ExecutionDetailPage';
import { SystemPage } from '../features/system/SystemPage';
import { ReadingPage } from '../features/reading/ReadingPage';
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
      { path: 'reading', element: <ReadingPage /> },
      { path: 'knowledge-bases/:kbId/reading', element: <ReadingPage /> },
      { path: 'knowledge-bases/:kbId/reading/:taskId', element: <ReadingPage /> },
      { path: 'knowledge-bases/:kbId', element: <KnowledgeBaseDetailPage /> },
      {
        path: 'knowledge-bases/:kbId/documents/:documentId',
        element: <DocumentDetailPage />,
      },
      {
        path: 'knowledge-bases/:kbId/chat',
        element: <ChatPage />,
      },
      {
        path: 'knowledge-bases/:kbId/chat/:conversationId',
        element: <ChatPage />,
      },
      {
        path: 'agent-executions',
        element: <ExecutionListPage />,
      },
      {
        path: 'agent-executions/:executionId',
        element: <ExecutionDetailPage />,
      },
      {
        path: 'system',
        element: <SystemPage />,
      },
      {
        path: 'evaluation',
        element: <FuturePage title="Benchmark 报告" description="评测报告浏览功能尚未开放。" />,
      },
      { path: '*', element: <NotFoundPage /> },
    ],
  },
]);
