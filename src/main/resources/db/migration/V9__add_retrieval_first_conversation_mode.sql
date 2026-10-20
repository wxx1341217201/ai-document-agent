-- M14: 新会话默认 RETRIEVAL_FIRST；既有 RAG 会话继续可读、可执行。
ALTER TABLE conversation DROP CHECK ck_conversation_agent_mode;
ALTER TABLE conversation
    ADD CONSTRAINT ck_conversation_agent_mode
        CHECK (agent_mode IN ('RAG', 'RETRIEVAL_FIRST'));
