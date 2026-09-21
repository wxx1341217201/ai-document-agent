import {
  DeleteOutlined,
  EditOutlined,
  EyeOutlined,
  PlusOutlined,
  SearchOutlined,
} from '@ant-design/icons';
import { Button, Input, Table, Tooltip, message } from 'antd';
import type { TableColumnsType } from 'antd';
import { useMemo, useState } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import type { KnowledgeBase, UpsertKnowledgeBaseRequest } from '../../api/contracts';
import { ConfirmDeleteModal, EmptyState, PageError, PageLoading } from '../../components/feedback';
import { PageHeader } from '../../components/layout/PageHeader';
import { useUiStore } from '../../stores/ui.store';
import { formatDateTime } from '../../utils/format';
import { KnowledgeBaseFormModal } from './KnowledgeBaseFormModal';
import {
  useCreateKnowledgeBase,
  useDeleteKnowledgeBase,
  useKnowledgeBaseList,
  useUpdateKnowledgeBase,
} from './knowledge-base.hooks';

const DEFAULT_PAGE_SIZE = 20;

export function KnowledgeBaseListPage() {
  const navigate = useNavigate();
  const [page, setPage] = useState(0);
  const [size, setSize] = useState(DEFAULT_PAGE_SIZE);
  const [searchText, setSearchText] = useState('');
  const [createOpen, setCreateOpen] = useState(false);
  const [editTarget, setEditTarget] = useState<KnowledgeBase>();
  const [deleteTarget, setDeleteTarget] = useState<KnowledgeBase>();
  const clearCurrentKnowledgeBase = useUiStore((state) => state.setCurrentKnowledgeBase);

  const listQuery = useKnowledgeBaseList({ page, size });
  const createMutation = useCreateKnowledgeBase();
  const updateMutation = useUpdateKnowledgeBase();
  const deleteMutation = useDeleteKnowledgeBase();

  const filteredKnowledgeBases = useMemo(() => {
    const content = listQuery.data?.content ?? [];
    const keyword = searchText.trim().toLocaleLowerCase();
    return keyword
      ? content.filter((knowledgeBase) => knowledgeBase.name.toLocaleLowerCase().includes(keyword))
      : content;
  }, [listQuery.data?.content, searchText]);

  const columns: TableColumnsType<KnowledgeBase> = [
    {
      title: '名称',
      dataIndex: 'name',
      render: (name: string, record) => <Link to={`/knowledge-bases/${record.id}`}>{name}</Link>,
    },
    {
      title: '描述',
      dataIndex: 'description',
      ellipsis: true,
      render: (description: string | null) => description || '—',
    },
    {
      title: '创建时间',
      dataIndex: 'createdAt',
      width: 170,
      render: formatDateTime,
    },
    {
      title: '更新时间',
      dataIndex: 'updatedAt',
      width: 170,
      render: formatDateTime,
    },
    {
      title: '操作',
      key: 'actions',
      width: 180,
      render: (_, record) => (
        <span className="table-actions">
          <Tooltip title="进入知识库">
            <Button
              type="link"
              size="small"
              icon={<EyeOutlined />}
              aria-label={`查看 ${record.name}`}
              onClick={() => navigate(`/knowledge-bases/${record.id}`)}
            >
              进入
            </Button>
          </Tooltip>
          <Button type="link" size="small" icon={<EditOutlined />} onClick={() => openEdit(record)}>
            编辑
          </Button>
          <Button
            type="link"
            danger
            size="small"
            icon={<DeleteOutlined />}
            onClick={() => openDelete(record)}
          >
            删除
          </Button>
        </span>
      ),
    },
  ];

  function openEdit(knowledgeBase: KnowledgeBase) {
    updateMutation.reset();
    setEditTarget(knowledgeBase);
  }

  function openDelete(knowledgeBase: KnowledgeBase) {
    deleteMutation.reset();
    setDeleteTarget(knowledgeBase);
  }

  function submitCreate(values: UpsertKnowledgeBaseRequest) {
    createMutation.mutate(values, {
      onSuccess: () => {
        message.success('知识库已创建');
        setCreateOpen(false);
        setPage(0);
      },
    });
  }

  function submitEdit(values: UpsertKnowledgeBaseRequest) {
    if (!editTarget) {
      return;
    }
    updateMutation.mutate(
      { knowledgeBaseId: editTarget.id, input: values },
      {
        onSuccess: (knowledgeBase) => {
          message.success('知识库已更新');
          setEditTarget(undefined);
          clearCurrentKnowledgeBase({ id: knowledgeBase.id, name: knowledgeBase.name });
        },
      },
    );
  }

  function confirmDelete() {
    if (!deleteTarget) {
      return;
    }
    deleteMutation.mutate(deleteTarget.id, {
      onSuccess: () => {
        message.success('知识库已删除');
        clearCurrentKnowledgeBase(undefined);
        setDeleteTarget(undefined);
      },
    });
  }

  const hasContent = filteredKnowledgeBases.length > 0;
  const isFilteredEmpty = Boolean(listQuery.data?.content.length) && !hasContent;

  return (
    <>
      <PageHeader
        title="知识库"
        description="按课题、项目或资料范围组织可检索的文档。"
        actions={
          <Button
            type="primary"
            icon={<PlusOutlined />}
            onClick={() => {
              createMutation.reset();
              setCreateOpen(true);
            }}
          >
            新建知识库
          </Button>
        }
      />

      <Input
        className="knowledge-base-search"
        value={searchText}
        prefix={<SearchOutlined />}
        placeholder="按名称搜索当前页"
        allowClear
        onChange={(event) => setSearchText(event.target.value)}
      />

      {listQuery.isLoading ? <PageLoading label="正在加载知识库…" /> : null}
      {listQuery.isError ? (
        <PageError error={listQuery.error} onRetry={() => void listQuery.refetch()} />
      ) : null}
      {listQuery.isSuccess && !hasContent ? (
        <EmptyState
          title={isFilteredEmpty ? '当前页没有匹配的知识库' : '还没有知识库'}
          description={
            isFilteredEmpty
              ? '搜索仅筛选当前页；可清除搜索或翻页继续查找。'
              : '创建知识库后即可开始上传文档。'
          }
          action={
            !isFilteredEmpty ? (
              <Button type="primary" icon={<PlusOutlined />} onClick={() => setCreateOpen(true)}>
                新建知识库
              </Button>
            ) : undefined
          }
        />
      ) : null}
      {listQuery.isSuccess && hasContent ? (
        <Table
          className="knowledge-base-table"
          rowKey="id"
          columns={columns}
          dataSource={filteredKnowledgeBases}
          pagination={{
            current: page + 1,
            pageSize: size,
            total: listQuery.data.totalElements,
            showSizeChanger: true,
            showTotal: (total) => `共 ${total} 个知识库`,
          }}
          onChange={(pagination) => {
            const nextSize = pagination.pageSize ?? size;
            setSize(nextSize);
            setPage(nextSize === size ? (pagination.current ?? 1) - 1 : 0);
          }}
        />
      ) : null}

      <KnowledgeBaseFormModal
        open={createOpen}
        mode="create"
        loading={createMutation.isPending}
        error={createMutation.error}
        onCancel={() => setCreateOpen(false)}
        onSubmit={submitCreate}
      />
      <KnowledgeBaseFormModal
        open={Boolean(editTarget)}
        mode="edit"
        initialValues={
          editTarget
            ? { name: editTarget.name, description: editTarget.description ?? undefined }
            : undefined
        }
        loading={updateMutation.isPending}
        error={updateMutation.error}
        onCancel={() => setEditTarget(undefined)}
        onSubmit={submitEdit}
      />
      <ConfirmDeleteModal
        open={Boolean(deleteTarget)}
        title="删除知识库"
        description={
          deleteTarget
            ? `确定删除“${deleteTarget.name}”吗？此操作无法撤销。`
            : '确定删除此知识库吗？'
        }
        loading={deleteMutation.isPending}
        error={deleteMutation.error}
        onCancel={() => setDeleteTarget(undefined)}
        onConfirm={confirmDelete}
      />
    </>
  );
}
