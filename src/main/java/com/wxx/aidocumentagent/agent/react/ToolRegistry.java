package com.wxx.aidocumentagent.agent.react;

import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;

import com.wxx.aidocumentagent.agent.AgentErrorCode;
import com.wxx.aidocumentagent.agent.AgentExecutionProbe.ToolAction;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.util.json.schema.JsonSchemaGenerator;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** 唯一工具白名单；Spring AI 回调仅用于向模型声明 schema，执行始终经过本类的校验。 */
public final class ToolRegistry {

    private static final int MAX_ARGUMENT_CHARACTERS = 8_192;
    private final Map<String, AgentTool<?, ?>> tools;
    private final ObjectMapper mapper;
    private final Validator validator;

    public ToolRegistry(List<AgentTool<?, ?>> registeredTools, ObjectMapper mapper, Validator validator) {
        this.mapper = Objects.requireNonNull(mapper);
        this.validator = Objects.requireNonNull(validator);
        Map<String, AgentTool<?, ?>> byName = new LinkedHashMap<>();
        for (AgentTool<?, ?> tool : registeredTools) {
            Objects.requireNonNull(tool);
            if (!tool.name().matches("[A-Za-z][A-Za-z0-9]{0,63}") || tool.inputType() == null
                    || !tool.inputType().isRecord() || byName.putIfAbsent(tool.name(), tool) != null) {
                throw new IllegalArgumentException("工具注册不合法或名称重复");
            }
        }
        this.tools = Map.copyOf(byName);
    }

    public ToolCallback[] advertisedCallbacks() {
        return tools.values().stream().map(tool -> new ToolCallback() {
            private final ToolDefinition definition = ToolDefinition.builder()
                    .name(tool.name()).description(tool.description())
                    .inputSchema(JsonSchemaGenerator.generateForType(tool.inputType())).build();

            @Override
            public ToolDefinition getToolDefinition() { return definition; }

            @Override
            public String call(String toolInput) {
                throw new IllegalStateException("工具必须经受控循环执行");
            }
        }).toArray(ToolCallback[]::new);
    }

    public boolean isRegistered(String name) { return name != null && tools.containsKey(name); }

    public String callKey(String name, String json) {
        try {
            JsonNode node = mapper.readTree(json);
            if (node == null || !node.isObject()) return name + ":invalid";
            Map<String, String> sorted = new TreeMap<>();
            node.properties().forEach(entry -> sorted.put(entry.getKey(), entry.getValue().toString()));
            return name + ":" + mapper.writeValueAsString(sorted);
        }
        catch (RuntimeException exception) {
            return name + ":invalid";
        }
    }

    public Invocation execute(String name, String json, ToolExecutionContext context) {
        AgentTool<?, ?> tool = name == null ? null : tools.get(name);
        if (tool == null) return Invocation.error(AgentErrorCode.UNKNOWN_TOOL, Map.of());
        if (json == null || json.length() > MAX_ARGUMENT_CHARACTERS) {
            return Invocation.error(AgentErrorCode.INVALID_TOOL_ARGUMENTS, Map.of());
        }
        try {
            JsonNode tree = mapper.readTree(json);
            if (tree == null || !tree.isObject() || !allowedFields(tree, tool.inputType())) {
                return Invocation.error(AgentErrorCode.INVALID_TOOL_ARGUMENTS, Map.of());
            }
            return executeTyped(tool, json, context);
        }
        catch (JacksonException | IllegalArgumentException exception) {
            return Invocation.error(AgentErrorCode.INVALID_TOOL_ARGUMENTS, Map.of());
        }
    }

    /** 超时时也能留下安全参数摘要；校验失败时不保留入参。 */
    public Map<String, String> safeSummary(String name, String json) {
        AgentTool<?, ?> tool = name == null ? null : tools.get(name);
        if (tool == null || json == null || json.length() > MAX_ARGUMENT_CHARACTERS) return Map.of();
        try {
            JsonNode tree = mapper.readTree(json);
            if (tree == null || !tree.isObject() || !allowedFields(tree, tool.inputType())) return Map.of();
            return summarizeTyped(tool, json);
        }
        catch (RuntimeException exception) {
            return Map.of();
        }
    }

    private <I> Map<String, String> summarizeTyped(AgentTool<I, ?> tool, String json) {
        I input = mapper.readValue(json, tool.inputType());
        if (!validator.validate(input).isEmpty()) return Map.of();
        return Map.copyOf(tool.summarize(input));
    }

    private boolean allowedFields(JsonNode tree, Class<?> inputType) {
        Set<String> allowed = Arrays.stream(inputType.getRecordComponents())
                .map(RecordComponent::getName).collect(Collectors.toSet());
        return tree.properties().stream().allMatch(entry -> allowed.contains(entry.getKey()));
    }

    private <I, O> Invocation executeTyped(AgentTool<I, O> tool, String json, ToolExecutionContext context) {
        if (context.durable() && !tool.readOnly()) {
            return Invocation.error(AgentErrorCode.TOOL_REQUIRES_IDEMPOTENCY, Map.of());
        }
        I input = mapper.readValue(json, tool.inputType());
        Set<ConstraintViolation<I>> violations = validator.validate(input);
        if (!violations.isEmpty()) return Invocation.error(AgentErrorCode.INVALID_TOOL_ARGUMENTS, Map.of());
        Map<String, String> summary = Map.copyOf(tool.summarize(input));
        try {
            context.checkActive();
            ToolAction action = context.beforeTool(tool.name(), summary);
            if (action == ToolAction.FAIL) {
                context.observeToolResult(tool.name(), Map.of("status", "TOOL_FAILED"));
                return Invocation.error(AgentErrorCode.TOOL_FAILED, summary);
            }
            O output = tool.execute(input, context);
            context.checkActive();
            if (action == ToolAction.EMPTY_EVIDENCE && output instanceof EvidenceResult evidence) {
                context.observeToolResult(tool.name(), evidenceSummary(evidence, 0));
                return new Invocation(new EvidenceResult(List.of(), evidence.retrievalSummary()), null, summary);
            }
            Map<String, String> resultSummary = output instanceof EvidenceResult evidence
                    ? evidenceSummary(evidence, evidence.chunks().size())
                    : output instanceof GetDocumentMetadataTool.Output metadata
                        ? Map.of("status", "OK", "found", Boolean.toString(metadata.found()))
                        : Map.of("status", "OK");
            context.observeToolResult(tool.name(), resultSummary);
            return new Invocation(output, null, summary);
        }
        catch (RuntimeException exception) {
            return Invocation.error(AgentErrorCode.TOOL_FAILED, summary);
        }
    }

    private Map<String, String> evidenceSummary(EvidenceResult evidence, int count) {
        Map<String, String> summary = new TreeMap<>(evidence.retrievalSummary());
        summary.put("status", "OK");
        summary.put("evidenceChunks", Integer.toString(count));
        return Map.copyOf(summary);
    }

    public record Invocation(Object output, AgentErrorCode errorCode, Map<String, String> argumentsSummary) {
        public static Invocation error(AgentErrorCode code, Map<String, String> summary) {
            return new Invocation(null, code, summary);
        }
        public boolean success() { return errorCode == null; }
    }
}
