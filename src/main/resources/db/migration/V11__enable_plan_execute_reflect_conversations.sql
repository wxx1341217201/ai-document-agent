-- M16: expand the conversation mode whitelist without changing applied migrations.
ALTER TABLE conversation DROP CHECK ck_conversation_agent_mode;
ALTER TABLE conversation
    ADD CONSTRAINT ck_conversation_agent_mode
        CHECK (agent_mode IN ('RAG', 'RETRIEVAL_FIRST', 'REACT', 'PLAN_EXECUTE_REFLECT'));
