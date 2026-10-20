package com.wxx.aidocumentagent.agent;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import com.wxx.aidocumentagent.common.api.BusinessException;

/** 只按枚举选择已注册实现，不包含任何具体 Agent 的业务逻辑。 */
public final class AgentRouter {

    private final Map<AgentMode, DocumentAgent> agents;

    public AgentRouter(List<DocumentAgent> registeredAgents) {
        Objects.requireNonNull(registeredAgents, "registeredAgents不能为空");
        EnumMap<AgentMode, DocumentAgent> byMode = new EnumMap<>(AgentMode.class);
        for (DocumentAgent agent : registeredAgents) {
            Objects.requireNonNull(agent, "agent不能为空");
            AgentMode mode = Objects.requireNonNull(agent.mode(), "Agent模式不能为空");
            if (byMode.putIfAbsent(mode, agent) != null) {
                throw new IllegalArgumentException("重复注册Agent模式: " + mode);
            }
        }
        this.agents = Map.copyOf(byMode);
    }

    public AgentMode resolveMode(AgentRequest request) {
        if (request == null) {
            throw new BusinessException(AgentErrorCode.INVALID_CONTEXT);
        }
        return request.mode() == null ? AgentMode.RETRIEVAL_FIRST : request.mode();
    }

    DocumentAgent route(AgentRequest request) {
        DocumentAgent agent = agents.get(resolveMode(request));
        if (agent == null) {
            throw new BusinessException(AgentErrorCode.AGENT_UNAVAILABLE);
        }
        return agent;
    }

    public AgentResult execute(AgentRequest request, AgentExecutionContext context) {
        if (context == null) {
            throw new BusinessException(AgentErrorCode.INVALID_CONTEXT);
        }
        DocumentAgent agent = route(request);
        context.claimFor(request);
        try {
            context.checkActive();
            AgentResult result = agent.execute(request, context);
            context.checkActive();
            if (result == null) {
                throw new BusinessException(AgentErrorCode.INVALID_RESULT);
            }
            if (result.citations().stream().anyMatch(citation -> citation.knowledgeBaseId() != request.knowledgeBaseId())) {
                throw new BusinessException(AgentErrorCode.SCOPE_VIOLATION);
            }
            if (result.status() != AgentResult.Status.COMPLETED) {
                context.publish(resolveMode(request), AgentEvent.Kind.FAILED, null,
                        result.failure() == null ? AgentErrorCode.INVALID_RESULT : result.failure().code());
            }
            else if (!context.completionDeferred()) {
                context.publish(resolveMode(request), AgentEvent.Kind.COMPLETED, null, null);
            }
            return result;
        }
        catch (BusinessException exception) {
            context.publish(resolveMode(request), AgentEvent.Kind.FAILED, null,
                    exception.getErrorCode() instanceof AgentErrorCode agentCode
                            ? agentCode : AgentErrorCode.EXECUTION_FAILED);
            throw exception;
        }
        catch (RuntimeException exception) {
            context.publish(resolveMode(request), AgentEvent.Kind.FAILED, null, AgentErrorCode.EXECUTION_FAILED);
            throw new BusinessException(AgentErrorCode.EXECUTION_FAILED,
                    AgentErrorCode.EXECUTION_FAILED.defaultMessage(), exception);
        }
    }
}
