package com.wxx.aidocumentagent.agent.execution.api.dto;

import com.wxx.aidocumentagent.agent.AgentMode;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

public record CreateExecutionRequest(@Positive long knowledgeBaseId, @Positive Long conversationId,
                                     AgentMode agentMode, @NotBlank @Size(max = 2000) String question) { }
