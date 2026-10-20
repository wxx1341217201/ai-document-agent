package com.wxx.aidocumentagent.progress;

import java.util.Set;

/** Fixed safe fields only. No model output, tool arguments, error detail or stack trace. */
public record ProgressPayload(String stepId, String status, String summary, String toolName,
                              Long documentId, String errorCode) {
    private static final Set<String> SAFE_SUMMARIES = Set.of("文档已排队", "正在解析文档", "正在切分文档",
            "正在建立索引", "文档已就绪", "文档处理失败", "执行已开始", "计划已创建", "步骤状态已更新",
            "工具调用已结束", "执行已完成", "执行已结束");

    public ProgressPayload {
        if (!machineId(stepId) || !machineId(toolName)
                || status == null || !status.matches("[A-Z][A-Z0-9_]{0,31}")
                || (errorCode != null && !errorCode.matches("[A-Z][A-Z0-9_]{0,63}"))
                || (documentId != null && documentId <= 0)
                || (summary != null && !SAFE_SUMMARIES.contains(summary))) {
            throw new IllegalArgumentException("Unsafe progress payload");
        }
    }

    private static boolean machineId(String value) {
        return value == null || value.matches("[A-Za-z][A-Za-z0-9_.-]{0,63}");
    }

    public static ProgressPayload document(long documentId, String status, String summary, String errorCode) {
        return new ProgressPayload(null, status, summary, null, documentId, errorCode);
    }

    public static ProgressPayload agent(String stepId, String status, String summary, String toolName,
                                        String errorCode) {
        return new ProgressPayload(stepId, status, summary, toolName, null, errorCode);
    }
}
