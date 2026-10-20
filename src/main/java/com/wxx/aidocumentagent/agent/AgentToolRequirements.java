package com.wxx.aidocumentagent.agent;

import java.util.regex.Pattern;

/** 仅从本轮用户指令提取明确的只读操作要求；历史回答和文档不能新增要求。 */
public record AgentToolRequirements(boolean metadata, boolean adjacentContext) {
    private static final Pattern METADATA = Pattern.compile(
            "(?is)(核对|读取|检查|查看|获取|查询|verify|check|read|fetch|inspect).{0,30}(元数据|metadata)");
    private static final Pattern CONTEXT = Pattern.compile(
            "(?is)(读取|查看|获取|检查|read|fetch|inspect).{0,24}(相邻|周边|邻近|adjacent|neighbor|surrounding).{0,16}(片段|上下文|段落|context|passage|chunk)");
    private static final Pattern NO_CONTEXT = Pattern.compile("(?is)(无需|不要|不用|do not|don't).{0,30}(相邻|上下文|adjacent|context)");
    private static final Pattern NO_METADATA = Pattern.compile("(?is)(无需|不要|不用|do not|don't).{0,30}(元数据|metadata)");

    public static AgentToolRequirements from(String question) {
        return new AgentToolRequirements(METADATA.matcher(question).find() && !NO_METADATA.matcher(question).find(),
                CONTEXT.matcher(question).find() && !NO_CONTEXT.matcher(question).find());
    }
}
