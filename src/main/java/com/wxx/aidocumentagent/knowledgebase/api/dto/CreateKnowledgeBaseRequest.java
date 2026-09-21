package com.wxx.aidocumentagent.knowledgebase.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 创建知识库请求；名称会在 Bean Validation 校验前完成标准化。
 */
public record CreateKnowledgeBaseRequest(
        @NotBlank(message = "名称不能为空")
        @Size(max = 128, message = "名称不能超过128个字符")
        String name,
        @Size(max = 512, message = "描述不能超过512个字符")
        String description) {

    public CreateKnowledgeBaseRequest {
        if (name != null) {
            name = name.strip();
        }
    }
}
