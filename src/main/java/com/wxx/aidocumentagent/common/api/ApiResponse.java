package com.wxx.aidocumentagent.common.api;

import java.util.Objects;

/**
 * 所有 REST 接口统一使用的响应包络。
 *
 * @param code    稳定、可供程序识别的结果码
 * @param message 面向 API 调用方的安全消息
 * @param data    响应载荷，或校验失败时的字段详情
 * @param traceId 用于关联服务端日志的请求标识
 * @param <T>     载荷类型
 */
public record ApiResponse<T>(String code, String message, T data, String traceId) {

    public static final String SUCCESS_CODE = "SUCCESS";
    public static final String SUCCESS_MESSAGE = "OK";

    public ApiResponse {
        Objects.requireNonNull(code, "结果码不能为空");
        Objects.requireNonNull(message, "响应消息不能为空");
        Objects.requireNonNull(traceId, "追踪标识不能为空");
    }

    public static <T> ApiResponse<T> success(T data, String traceId) {
        return new ApiResponse<>(SUCCESS_CODE, SUCCESS_MESSAGE, data, traceId);
    }

    public static <T> ApiResponse<T> failure(ErrorCode errorCode, String message, T data, String traceId) {
        Objects.requireNonNull(errorCode, "错误码不能为空");
        return new ApiResponse<>(errorCode.code(), message, data, traceId);
    }
}
