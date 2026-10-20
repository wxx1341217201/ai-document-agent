package com.wxx.aidocumentagent.recovery.benchmark;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import com.wxx.aidocumentagent.conversation.api.dto.ConversationMessageResponse;
import com.wxx.aidocumentagent.conversation.domain.ConversationMessageRole;
import com.wxx.aidocumentagent.conversation.infrastructure.memory.ConversationMemoryEntry;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

public final class RecoveryBenchmarkObservation {
    private RecoveryBenchmarkObservation() { }

    public record Message(long id, long sequenceNo, ConversationMessageRole role, String contentSha256,
                          String citationsSha256) {
        public static Message from(ConversationMessageResponse message, ObjectMapper mapper) {
            return new Message(message.id(), message.sequenceNo(), message.role(), sha(message.content()),
                    hash(mapper, message.citations()));
        }
    }

    public record Memory(long sequenceNo, ConversationMessageRole role, String contentSha256) {
        public static Memory from(ConversationMemoryEntry entry) {
            return new Memory(entry.sequenceNo(), entry.role(), sha(entry.content()));
        }
    }

    public record Snapshot(long conversationId, long knowledgeBaseId, long lastMessageSequence,
                           List<Message> messages, List<Memory> memory, boolean cachePresent) {
        public Snapshot { messages = List.copyOf(messages); memory = List.copyOf(memory); }
    }

    public record Execution(String executionId, long knowledgeBaseId, Long conversationId, String status,
                            String answerSha256, String resultSha256, Map<String, String> steps,
                            int toolInvocations, String errorCode) {
        public Execution { steps = Map.copyOf(steps); }
        public static Execution from(JsonNode node, ObjectMapper mapper) {
            Map<String, String> steps = new TreeMap<>();
            for (JsonNode step : node.path("steps")) steps.put(step.path("stepId").asText(), step.path("status").asText());
            JsonNode result = node.path("result");
            return new Execution(node.path("executionId").asText(), node.path("knowledgeBaseId").asLong(),
                    node.path("conversationId").isNumber() ? node.path("conversationId").asLong() : null,
                    node.path("status").asText(), result.path("answer").isTextual() ? sha(result.path("answer").asText()) : null,
                    result.isMissingNode() || result.isNull() ? null : hash(mapper, result), steps,
                    node.path("toolCalls").size(), node.path("errorCode").isTextual() ? node.path("errorCode").asText() : null);
        }
    }

    public static String sha(String value) {
        return sha(value.getBytes(StandardCharsets.UTF_8));
    }
    public static String sha(byte[] value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value)); }
        catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }

    /** JSON object order is not significant, including MySQL JSON reserialization. Array order is significant. */
    public static String hash(ObjectMapper mapper, Object value) {
        return sha(mapper.writeValueAsString(canonical(mapper.valueToTree(value))));
    }

    private static Object canonical(JsonNode node) {
        if (node.isObject()) {
            Map<String, Object> result = new TreeMap<>();
            node.properties().forEach(entry -> result.put(entry.getKey(), canonical(entry.getValue())));
            return result;
        }
        if (node.isArray()) {
            List<Object> result = new java.util.ArrayList<>();
            node.forEach(item -> result.add(canonical(item)));
            return result;
        }
        return node;
    }
}
