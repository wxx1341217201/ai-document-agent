import { Form, Input, Modal } from 'antd';
import { useEffect } from 'react';
import type { UpsertKnowledgeBaseRequest } from '../../api/contracts';
import { RequestErrorAlert } from '../../components/feedback';

export function KnowledgeBaseFormModal({
  open,
  mode,
  initialValues,
  loading,
  error,
  onCancel,
  onSubmit,
}: {
  open: boolean;
  mode: 'create' | 'edit';
  initialValues?: UpsertKnowledgeBaseRequest;
  loading?: boolean;
  error?: unknown;
  onCancel: () => void;
  onSubmit: (values: UpsertKnowledgeBaseRequest) => void;
}) {
  const [form] = Form.useForm<UpsertKnowledgeBaseRequest>();

  useEffect(() => {
    if (open) {
      form.setFieldsValue({
        name: initialValues?.name ?? '',
        description: initialValues?.description ?? '',
      });
    }
  }, [form, initialValues, open]);

  return (
    <Modal
      open={open}
      title={mode === 'create' ? '新建知识库' : '编辑知识库'}
      okText={mode === 'create' ? '创建' : '保存'}
      cancelText="取消"
      confirmLoading={loading}
      destroyOnClose
      onCancel={onCancel}
      onOk={() => form.submit()}
    >
      {error ? <RequestErrorAlert error={error} /> : null}
      <Form
        form={form}
        layout="vertical"
        requiredMark="optional"
        onFinish={(values) => onSubmit(values)}
      >
        <Form.Item
          label="名称"
          name="name"
          rules={[
            { required: true, whitespace: true, message: '请输入知识库名称。' },
            { max: 128, message: '名称不能超过 128 个字符。' },
          ]}
        >
          <Input autoFocus maxLength={128} showCount placeholder="例如：CO₂ 催化研究" />
        </Form.Item>
        <Form.Item
          label="描述"
          name="description"
          rules={[{ max: 512, message: '描述不能超过 512 个字符。' }]}
        >
          <Input.TextArea
            rows={4}
            maxLength={512}
            showCount
            placeholder="可选，简要说明资料范围。"
          />
        </Form.Item>
      </Form>
    </Modal>
  );
}
