import { Form, Input, Modal, Select, Typography } from 'antd';
import { useEffect } from 'react';
import type { CreateConversationRequest } from '../../api/contracts';
import { RequestErrorAlert } from '../../components/feedback';
import { newConversationModes } from './agent-modes';

export function CreateConversationModal({
  open,
  loading,
  error,
  onCancel,
  onSubmit,
}: {
  open: boolean;
  loading: boolean;
  error: unknown;
  onCancel: () => void;
  onSubmit: (input: CreateConversationRequest) => void;
}) {
  const [form] = Form.useForm<CreateConversationRequest>();
  useEffect(() => {
    if (open) form.resetFields();
  }, [form, open]);

  return (
    <Modal
      title="创建会话"
      open={open}
      okText="创建"
      cancelText="取消"
      confirmLoading={loading}
      destroyOnHidden
      closable={!loading}
      maskClosable={!loading}
      keyboard={!loading}
      cancelButtonProps={{ disabled: loading }}
      onCancel={onCancel}
      onOk={() => form.submit()}
    >
      {error ? <RequestErrorAlert error={error} /> : null}
      <Form
        form={form}
        layout="vertical"
        disabled={loading}
        initialValues={{ title: '', agentMode: 'RETRIEVAL_FIRST' }}
        onFinish={onSubmit}
      >
        <Form.Item
          label="会话标题"
          name="title"
          rules={[{ max: 128, message: '标题不能超过 128 个字符。' }]}
        >
          <Input maxLength={128} placeholder="可选，留空使用默认标题" />
        </Form.Item>
        <Form.Item label="Agent 模式" name="agentMode" rules={[{ required: true }]}>
          <Select options={newConversationModes} />
        </Form.Item>
        <Typography.Paragraph type="secondary">
          创建后模式固定；如需切换模式，请新建一个会话。
        </Typography.Paragraph>
      </Form>
    </Modal>
  );
}
