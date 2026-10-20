-- M15: REACT 会话只保存已完成回答及结构化步骤、工具审计摘要。
ALTER TABLE conversation DROP CHECK ck_conversation_agent_mode;
ALTER TABLE conversation
    ADD CONSTRAINT ck_conversation_agent_mode
        CHECK (agent_mode IN ('RAG', 'RETRIEVAL_FIRST', 'REACT'));

ALTER TABLE conversation_message
    ADD COLUMN agent_steps_json JSON NULL COMMENT '结构化步骤状态，不含模型隐藏推理',
    ADD COLUMN tool_calls_json JSON NULL COMMENT '工具名称、参数摘要、耗时、状态与错误码';
