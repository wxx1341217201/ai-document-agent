package com.wxx.aidocumentagent.common.api;

/**
 * 单个未通过校验的输入字段；不会返回被拒绝的原始值。
 */
public record FieldValidationError(String field, String message) {
}
