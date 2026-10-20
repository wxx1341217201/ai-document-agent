CREATE INDEX idx_execution_updated_id ON agent_execution (updated_at, execution_id);
CREATE INDEX idx_execution_status_updated_id ON agent_execution (status, updated_at, execution_id);
CREATE INDEX idx_execution_mode_updated_id ON agent_execution (agent_mode, updated_at, execution_id);
