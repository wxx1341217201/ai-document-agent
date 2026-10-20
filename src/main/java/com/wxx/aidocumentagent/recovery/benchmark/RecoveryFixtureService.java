package com.wxx.aidocumentagent.recovery.benchmark;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.wxx.aidocumentagent.agent.AgentErrorCode;
import com.wxx.aidocumentagent.agent.AgentExecutionContext;
import com.wxx.aidocumentagent.agent.execution.ExecutionErrorCode;
import com.wxx.aidocumentagent.agent.execution.application.ExecutionStore;
import com.wxx.aidocumentagent.common.api.BusinessException;
import com.wxx.aidocumentagent.conversation.application.ConversationMemoryService;
import com.wxx.aidocumentagent.conversation.application.ConversationMessagePayloadCodec;
import com.wxx.aidocumentagent.conversation.infrastructure.persistence.*;
import com.wxx.aidocumentagent.knowledgebase.infrastructure.persistence.*;
import org.springframework.core.env.Environment;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.redis.core.StringRedisTemplate;
import tools.jackson.databind.ObjectMapper;

/** Fixture ownership and fault rules stay in the application service, never in the controller. */
public final class RecoveryFixtureService {
    private final KnowledgeBaseRepository knowledgeBases;
    private final ConversationRepository conversations;
    private final ConversationMessageRepository messages;
    private final ConversationMemoryService memory;
    private final ConversationMessagePayloadCodec codec;
    private final StringRedisTemplate redis;
    private final ExecutionStore executions;
    private final ObjectMapper mapper;
    private final String runId, token;
    private final Path runtime;
    private final Map<String, Thread> waiting = new ConcurrentHashMap<>();
    private long knowledgeBaseId, decoyKnowledgeBaseId;

    RecoveryFixtureService(KnowledgeBaseRepository knowledgeBases, ConversationRepository conversations,
                           ConversationMessageRepository messages, ConversationMemoryService memory,
                           ConversationMessagePayloadCodec codec, StringRedisTemplate redis, ExecutionStore executions,
                           ObjectMapper mapper, Environment environment) {
        this.knowledgeBases = knowledgeBases; this.conversations = conversations; this.messages = messages;
        this.memory = memory; this.codec = codec; this.redis = redis; this.executions = executions; this.mapper = mapper;
        this.runId = environment.getRequiredProperty("M19D_RUN_ID");
        java.util.UUID.fromString(runId);
        this.token = environment.getRequiredProperty("M19D_ACCESS_TOKEN");
        if (token.length() < 32) throw new IllegalArgumentException("BENCHMARK_TOKEN_TOO_SHORT");
        this.runtime = Path.of(environment.getRequiredProperty("M19D_RUNTIME_DIRECTORY")).toAbsolutePath().normalize();
    }
    public void initialize() throws IOException {
        Files.createDirectories(runtime);
        knowledgeBaseId = ownedKnowledgeBase("m19d-" + runId);
        decoyKnowledgeBaseId = ownedKnowledgeBase("m19d-decoy-" + runId);
    }
    private long ownedKnowledgeBase(String name) {
        return knowledgeBases.findOne(org.springframework.data.domain.Example.of(KnowledgeBase.create(name, null)))
                .orElseGet(() -> knowledgeBases.saveAndFlush(KnowledgeBase.create(name,
                "M19D isolated fault injection fixture"))).getId();
    }
    public Map<String, Object> metadata() {
        return Map.of("runId", runId, "pid", ProcessHandle.current().pid(), "knowledgeBaseId", knowledgeBaseId,
                "decoyKnowledgeBaseId", decoyKnowledgeBaseId, "profile", "CONTROLLED_AGENT_REAL_STORAGE_PROCESS_FAULTS");
    }
    public void authorize(String header) {
        if (header == null || !header.startsWith("Bearer ") || !MessageDigest.isEqual(token.getBytes(StandardCharsets.UTF_8),
                header.substring(7).getBytes(StandardCharsets.UTF_8))) throw new BusinessException(ExecutionErrorCode.NOT_FOUND);
    }
    public Conversation requireConversation(long kb, Long id) {
        if (id == null || kb != knowledgeBaseId) throw new BusinessException(ExecutionErrorCode.NOT_FOUND);
        return conversations.findByIdAndKnowledgeBaseId(id, kb)
                .filter(c -> c.getTitle().startsWith("m19d:" + runId + ":"))
                .orElseThrow(() -> new BusinessException(ExecutionErrorCode.NOT_FOUND));
    }
    public RecoveryBenchmarkObservation.Snapshot snapshot(long kb, long id) {
        Conversation conversation = requireConversation(kb, id);
        List<RecoveryBenchmarkObservation.Message> durable = new ArrayList<>();
        int page = 0;
        org.springframework.data.domain.Page<ConversationMessage> batch;
        do {
            batch = messages.findPageByConversationIdAndKnowledgeBaseId(id, kb, PageRequest.of(page++, 100));
            batch.forEach(m -> durable.add(RecoveryBenchmarkObservation.Message.from(codec.toResponse(m), mapper)));
        } while (batch.hasNext());
        var shortTerm = memory.load(conversation).stream().map(RecoveryBenchmarkObservation.Memory::from).toList();
        return new RecoveryBenchmarkObservation.Snapshot(id, kb, conversation.getLastMessageSequence(), durable,
                shortTerm, Boolean.TRUE.equals(redis.hasKey(key(id))));
    }
    public Map<String, Object> cache(long kb, long id, String operation) {
        requireConversation(kb, id);
        boolean present = Boolean.TRUE.equals(redis.hasKey(key(id)));
        if ("DELETE".equals(operation)) {
            if (!present || !Boolean.TRUE.equals(redis.delete(key(id)))) throw new IllegalStateException("CACHE_DELETE_NOT_APPLIED");
        }
        else if ("EXPIRE".equals(operation)) {
            if (!present || !Boolean.TRUE.equals(redis.expire(key(id), Duration.ofMillis(50)))) throw new IllegalStateException("CACHE_TTL_NOT_APPLIED");
        }
        else if (!"STATE".equals(operation)) throw new IllegalArgumentException("INVALID_CACHE_FAULT");
        return Map.of("knowledgeBaseId", kb, "conversationId", id, "operation", operation,
                "wasPresent", present, "present", Boolean.TRUE.equals(redis.hasKey(key(id))));
    }
    public Map<String, Object> interrupt(long kb, String id) {
        var execution = executions.get(id, kb);
        requireConversation(kb, execution.getConversationId());
        Thread thread = waiting.get(id);
        if (thread == null || !thread.isAlive()) throw new IllegalStateException("WORKER_NOT_AT_STEP_BOUNDARY");
        thread.interrupt();
        return Map.of("executionId", id, "workerThreadId", thread.threadId(), "interruptSent", true);
    }
    public Map<String, Object> attempts(long kb, String id) {
        requireConversation(kb, executions.get(id, kb).getConversationId());
        try {
            return Map.of("executionId", id, "agentAttempts", Files.exists(marker(id, ".calls")) ? Files.readAllLines(marker(id, ".calls")).size() : 0,
                    "waiting", waiting.containsKey(id));
        } catch (IOException e) { throw new IllegalStateException("ATTEMPT_JOURNAL_UNAVAILABLE"); }
    }
    void recordAttempt(String id) {
        try { Files.writeString(marker(id, ".calls"), "AGENT_ATTEMPT\n", StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND); }
        catch (IOException e) { throw new IllegalStateException("ATTEMPT_JOURNAL_UNAVAILABLE"); }
    }
    void awaitRelease(String id, AgentExecutionContext context) {
        waiting.put(id, Thread.currentThread());
        long deadline = System.nanoTime() + Duration.ofMinutes(2).toNanos();
        long nextActiveCheck = System.nanoTime();
        try {
            while (!Files.exists(marker(id, ".release"))) {
                // checkActive performs a durable cancellation lookup. Do not flood MySQL while holding a test boundary.
                if (context != null && System.nanoTime() >= nextActiveCheck) {
                    context.checkActive();
                    nextActiveCheck = System.nanoTime() + Duration.ofMillis(250).toNanos();
                }
                if (System.nanoTime() > deadline) throw new BusinessException(AgentErrorCode.TOOL_TIMEOUT);
                try { Thread.sleep(25); }
                catch (InterruptedException e) {
                    // This controlled boundary fault becomes a retryable domain error; allow M17 to commit FAILED.
                    throw new BusinessException(AgentErrorCode.TOOL_FAILED);
                }
            }
        } finally { waiting.remove(id); }
    }
    void afterResultCheckpoint(String id, long kb) {
        var execution = executions.get(id, kb);
        requireConversation(kb, execution.getConversationId());
        if (execution.getQuestion().endsWith("|RESULT")) awaitRelease(id, null);
    }
    private Path marker(String id, String suffix) {
        if (!java.util.UUID.fromString(id).toString().equalsIgnoreCase(id)) throw new IllegalArgumentException("INVALID_EXECUTION_ID");
        return runtime.resolve(id + suffix);
    }
    private String key(long id) { return "chat:memory:" + id; }
    Path runtimeDirectory() { return runtime; }
    ObjectMapper mapper() { return mapper; }
}
