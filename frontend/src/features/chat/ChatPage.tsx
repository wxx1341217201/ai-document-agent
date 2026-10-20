import {
  ArrowLeftOutlined,
  DeleteOutlined,
  MenuOutlined,
  PlusOutlined,
  SettingOutlined,
} from '@ant-design/icons';
import { App, Button, Drawer, Space } from 'antd';
import { useEffect, useRef, useState } from 'react';
import { useLocation, useNavigate, useParams } from 'react-router-dom';
import type { Conversation, KnowledgeBase } from '../../api/contracts';
import {
  ConfirmDeleteModal,
  EmptyState,
  ErrorResult,
  PageError,
  PageLoading,
  RequestErrorAlert,
} from '../../components/feedback';
import { PageHeader } from '../../components/layout/PageHeader';
import { useUiStore } from '../../stores/ui.store';
import { formatDateTime } from '../../utils/format';
import { parsePositiveRouteId } from '../../utils/route-params';
import { useKnowledgeBase } from '../knowledge-base/knowledge-base.hooks';
import { agentModeLabels } from './agent-modes';
import { useIsAgentBusy } from './agent.hooks';
import { ChatThread } from './ChatThread';
import { CreateConversationModal } from './CreateConversationModal';
import { NewConversationComposer } from './NewConversationComposer';
import {
  useConversationList,
  useCreateConversation,
  useDeleteConversation,
} from './conversation.hooks';

export function ChatPage() {
  const { kbId, conversationId: rawConversationId } = useParams();
  const knowledgeBaseId = parsePositiveRouteId(kbId);
  const conversationId = parsePositiveRouteId(rawConversationId);
  const knowledgeBase = useKnowledgeBase(knowledgeBaseId);
  const setCurrentKnowledgeBase = useUiStore((state) => state.setCurrentKnowledgeBase);
  useEffect(() => {
    if (knowledgeBase.data)
      setCurrentKnowledgeBase({ id: knowledgeBase.data.id, name: knowledgeBase.data.name });
    return () => setCurrentKnowledgeBase(undefined);
  }, [knowledgeBase.data, setCurrentKnowledgeBase]);

  if (!knowledgeBaseId || (rawConversationId !== undefined && !conversationId)) {
    return (
      <ErrorResult status="404" title="会话地址无效" subTitle="请从知识库中选择有效的会话。" />
    );
  }
  if (knowledgeBase.isLoading) return <PageLoading label="正在加载知识库…" />;
  if (knowledgeBase.isError)
    return <PageError error={knowledgeBase.error} onRetry={() => void knowledgeBase.refetch()} />;
  if (!knowledgeBase.data) return <EmptyState description="未找到知识库。" />;
  return (
    <ChatWorkspace
      key={knowledgeBaseId}
      knowledgeBase={knowledgeBase.data}
      conversationId={conversationId}
    />
  );
}

function ChatWorkspace({
  knowledgeBase,
  conversationId,
}: {
  knowledgeBase: KnowledgeBase;
  conversationId?: number;
}) {
  const navigate = useNavigate();
  const location = useLocation();
  const { message } = App.useApp();
  const list = useConversationList(knowledgeBase.id);
  const create = useCreateConversation(knowledgeBase.id);
  const remove = useDeleteConversation(knowledgeBase.id);
  const isAgentBusy = useIsAgentBusy();
  const [createOpen, setCreateOpen] = useState(false);
  const [sidebarOpen, setSidebarOpen] = useState(false);
  const [deleteTarget, setDeleteTarget] = useState<Conversation>();
  const createSubmitting = useRef(false);
  const activeId = useRef(conversationId);
  useEffect(() => {
    activeId.current = conversationId;
  }, [conversationId]);

  const conversations = [
    ...new Map(
      (list.data?.pages.flatMap((page) => page.content) ?? []).map((item) => [item.id, item]),
    ).values(),
  ];
  const selected = conversations.find((item) => item.id === conversationId);
  const routeMessage = location.state?.initialMessage;
  const initialMessage =
    typeof routeMessage?.id === 'string' &&
    typeof routeMessage?.content === 'string' &&
    routeMessage.content.trim().length > 0 &&
    routeMessage.content.length <= 2000
      ? { id: routeMessage.id, content: routeMessage.content }
      : undefined;
  const { hasNextPage, isFetching, isError, fetchNextPage } = list;

  // There is no GET conversation detail endpoint. Resolve bookmarked IDs through the KB list,
  // including later pages, before reading messages from the globally addressed endpoint.
  useEffect(() => {
    if (conversationId && !selected && hasNextPage && !isFetching && !isError) {
      void fetchNextPage();
    }
  }, [conversationId, selected, hasNextPage, isFetching, isError, fetchNextPage]);

  function openCreate() {
    create.reset();
    setSidebarOpen(false);
    setCreateOpen(true);
  }

  const sidebar = (
    <nav aria-label="会话列表" className="chat-sidebar-content">
      <div className="chat-sidebar-actions">
        <Button
          aria-label="新建会话"
          icon={<PlusOutlined />}
          onClick={() => {
            create.reset();
            setSidebarOpen(false);
            navigate(`/knowledge-bases/${knowledgeBase.id}/chat`);
          }}
          disabled={create.isPending}
        >
          新建会话
        </Button>
        <Button
          aria-label="高级会话"
          title="选择问答模式"
          icon={<SettingOutlined />}
          onClick={openCreate}
          disabled={create.isPending}
        />
      </div>
      {list.isLoading ? <PageLoading label="正在加载会话列表…" /> : null}
      {list.isError ? (
        <Space direction="vertical" className="chat-list-error">
          <RequestErrorAlert error={list.error} />
          <Button
            onClick={() => void (list.isFetchNextPageError ? list.fetchNextPage() : list.refetch())}
          >
            重试会话列表
          </Button>
        </Space>
      ) : null}
      {list.isSuccess && conversations.length === 0 ? (
        <EmptyState description="发送第一个问题后，会话会保存在这里。" />
      ) : null}
      <ul className="chat-conversation-list">
        {conversations.map((item) => (
          <li
            key={item.id}
            className={item.id === conversationId ? 'chat-conversation-selected' : ''}
          >
            <button
              type="button"
              className="chat-conversation-link"
              aria-label={'打开会话 ' + item.title}
              aria-current={item.id === conversationId ? 'page' : undefined}
              onClick={() => {
                setSidebarOpen(false);
                navigate(`/knowledge-bases/${knowledgeBase.id}/chat/${item.id}`);
              }}
            >
              <span className="chat-conversation-title">{item.title}</span>
              <span>{agentModeLabels[item.agentMode]}</span>
              <span>{formatDateTime(item.updatedAt)}</span>
            </button>
            <Button
              type="text"
              danger
              icon={<DeleteOutlined />}
              aria-label={'删除会话 ' + item.title}
              disabled={remove.isPending || isAgentBusy}
              onClick={() => {
                remove.reset();
                setDeleteTarget(item);
              }}
            />
          </li>
        ))}
      </ul>
      {list.hasNextPage ? (
        <Button block loading={list.isFetchingNextPage} onClick={() => void list.fetchNextPage()}>
          加载更多会话
        </Button>
      ) : null}
    </nav>
  );

  return (
    <>
      <PageHeader
        title="智能问答"
        description={knowledgeBase.name}
        actions={
          <Button
            icon={<ArrowLeftOutlined />}
            onClick={() => navigate(`/knowledge-bases/${knowledgeBase.id}`)}
          >
            返回知识库
          </Button>
        }
      />
      <Button
        aria-label="会话列表"
        className="chat-mobile-sidebar-toggle"
        icon={<MenuOutlined />}
        onClick={() => setSidebarOpen(true)}
      >
        会话列表
      </Button>
      <div className="chat-workspace">
        <aside className="chat-sidebar-desktop">{sidebar}</aside>
        <div className="chat-main">
          {!conversationId ? (
            <NewConversationComposer
              key={location.key}
              loading={create.isPending}
              error={create.error}
              onSend={(content) => {
                if (createSubmitting.current) return;
                createSubmitting.current = true;
                create.mutate(
                  {
                    title: [...content.replace(/\s+/g, ' ')].slice(0, 64).join(''),
                    agentMode: 'RETRIEVAL_FIRST',
                  },
                  {
                    onSuccess: (result) => {
                      navigate(`/knowledge-bases/${knowledgeBase.id}/chat/${result.id}`, {
                        state: { initialMessage: { id: crypto.randomUUID(), content } },
                      });
                    },
                    onSettled: () => {
                      createSubmitting.current = false;
                    },
                  },
                );
              }}
            />
          ) : selected ? (
            <ChatThread
              key={selected.id}
              conversation={selected}
              initialMessage={initialMessage}
              onInitialMessageConsumed={() =>
                navigate(location.pathname, { replace: true, state: null })
              }
            />
          ) : list.isError ? (
            <PageError
              title="会话加载失败"
              error={list.error}
              onRetry={() =>
                void (list.isFetchNextPageError ? list.fetchNextPage() : list.refetch())
              }
            />
          ) : list.isLoading || list.isFetching || list.hasNextPage ? (
            <PageLoading label="正在恢复会话…" />
          ) : (
            <ErrorResult
              status="404"
              title="会话不存在或不属于当前知识库"
              subTitle="请从左侧会话列表选择，或创建新会话。"
            />
          )}
        </div>
      </div>
      <Drawer
        title="会话列表"
        placement="left"
        open={sidebarOpen}
        onClose={() => setSidebarOpen(false)}
        width={300}
        destroyOnHidden
      >
        {sidebar}
      </Drawer>
      <CreateConversationModal
        open={createOpen}
        loading={create.isPending}
        error={create.error}
        onCancel={() => setCreateOpen(false)}
        onSubmit={(input) => {
          if (createSubmitting.current) return;
          createSubmitting.current = true;
          create.mutate(input, {
            onSuccess: (result) => {
              setCreateOpen(false);
              void message.success('会话已创建');
              navigate(`/knowledge-bases/${knowledgeBase.id}/chat/${result.id}`);
            },
            onSettled: () => {
              createSubmitting.current = false;
            },
          });
        }}
      />
      <ConfirmDeleteModal
        open={Boolean(deleteTarget)}
        title="删除会话"
        description={<>删除“{deleteTarget?.title}”及其全部消息？此操作无法撤销。</>}
        loading={remove.isPending}
        error={remove.error}
        onCancel={() => {
          if (!remove.isPending) setDeleteTarget(undefined);
        }}
        onConfirm={() => {
          if (!deleteTarget || remove.isPending || isAgentBusy) return;
          remove.mutate(deleteTarget.id, {
            onSuccess: (_, deletedId) => {
              setDeleteTarget(undefined);
              void message.success('会话已删除');
              if (activeId.current === deletedId)
                navigate(`/knowledge-bases/${knowledgeBase.id}/chat`, { replace: true });
            },
          });
        }}
      />
    </>
  );
}
