package com.wxx.aidocumentagent.agent;

import com.wxx.aidocumentagent.common.api.ErrorCode;
import org.springframework.http.HttpStatus;

/** 统一的安全错误分类；不会将实现异常或模型原始输出返回给客户端。 */
public enum AgentErrorCode implements ErrorCode {

    INVALID_MODE(Category.INPUT, HttpStatus.BAD_REQUEST, "Agent模式不合法"),
    INVALID_CONTEXT(Category.INPUT, HttpStatus.BAD_REQUEST, "Agent执行上下文与请求不匹配"),
    CONTEXT_REUSED(Category.CONFLICT, HttpStatus.CONFLICT, "Agent执行上下文已使用"),
    AGENT_UNAVAILABLE(Category.AVAILABILITY, HttpStatus.SERVICE_UNAVAILABLE, "所选Agent模式暂不可用"),
    EXECUTION_CANCELLED(Category.CANCELLATION, HttpStatus.CONFLICT, "Agent执行已取消"),
    DEADLINE_EXCEEDED(Category.TIMEOUT, HttpStatus.GATEWAY_TIMEOUT, "Agent执行超时"),
    MODEL_TIMEOUT(Category.TIMEOUT, HttpStatus.GATEWAY_TIMEOUT, "回答模型超时"),
    MODEL_UNAVAILABLE(Category.AVAILABILITY, HttpStatus.SERVICE_UNAVAILABLE, "回答模型暂不可用"),
    STORAGE_FAILED(Category.EXECUTION, HttpStatus.INTERNAL_SERVER_ERROR, "会话消息保存失败"),
    EXECUTION_FAILED(Category.EXECUTION, HttpStatus.INTERNAL_SERVER_ERROR, "Agent执行失败"),
    INVALID_RESULT(Category.EXECUTION, HttpStatus.INTERNAL_SERVER_ERROR, "Agent返回结果不合法"),
    SCOPE_VIOLATION(Category.ISOLATION, HttpStatus.INTERNAL_SERVER_ERROR, "Agent结果知识库边界不合法"),
    UNKNOWN_TOOL(Category.INPUT, HttpStatus.BAD_REQUEST, "工具未注册"),
    INVALID_TOOL_ARGUMENTS(Category.INPUT, HttpStatus.BAD_REQUEST, "工具参数不合法"),
    TOOL_FAILED(Category.EXECUTION, HttpStatus.SERVICE_UNAVAILABLE, "工具执行失败"),
    TOOL_REQUIRES_IDEMPOTENCY(Category.EXECUTION, HttpStatus.CONFLICT, "有副作用工具尚未实现独立幂等保护"),
    TOOL_TIMEOUT(Category.TIMEOUT, HttpStatus.GATEWAY_TIMEOUT, "工具执行超时"),
    REPEATED_TOOL_CALL(Category.EXECUTION, HttpStatus.CONFLICT, "工具调用重复过多"),
    STEP_LIMIT_EXCEEDED(Category.EXECUTION, HttpStatus.CONFLICT, "Agent步骤数已达上限"),
    INVALID_ANSWER(Category.EXECUTION, HttpStatus.INTERNAL_SERVER_ERROR, "Agent回答引用不合法"),
    INVALID_PLAN(Category.EXECUTION, HttpStatus.BAD_GATEWAY, "模型计划不合法"),
    TOOL_CALL_LIMIT_EXCEEDED(Category.EXECUTION, HttpStatus.CONFLICT, "工具调用次数已达上限"),
    MODEL_CALL_LIMIT_EXCEEDED(Category.EXECUTION, HttpStatus.CONFLICT, "模型调用次数已达上限");

    private final Category category;
    private final HttpStatus status;
    private final String defaultMessage;

    AgentErrorCode(Category category, HttpStatus status, String defaultMessage) {
        this.category = category;
        this.status = status;
        this.defaultMessage = defaultMessage;
    }

    public Category category() {
        return category;
    }

    @Override
    public String code() {
        return name();
    }

    @Override
    public HttpStatus status() {
        return status;
    }

    @Override
    public String defaultMessage() {
        return defaultMessage;
    }

    public enum Category {
        INPUT, CONFLICT, AVAILABILITY, CANCELLATION, TIMEOUT, EXECUTION, ISOLATION
    }
}
