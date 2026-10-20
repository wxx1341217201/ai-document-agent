package com.wxx.aidocumentagent.rag.application;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import com.wxx.aidocumentagent.agent.AgentCancellationToken;
import com.wxx.aidocumentagent.agent.AgentConversationContext;
import com.wxx.aidocumentagent.agent.AgentErrorCode;
import com.wxx.aidocumentagent.agent.AgentEvent;
import com.wxx.aidocumentagent.agent.AgentExecutionContext;
import com.wxx.aidocumentagent.agent.AgentMemoryMessage;
import com.wxx.aidocumentagent.agent.AgentMode;
import com.wxx.aidocumentagent.agent.AgentRequest;
import com.wxx.aidocumentagent.agent.AgentResult;
import com.wxx.aidocumentagent.agent.AgentRouter;
import com.wxx.aidocumentagent.agent.RetrievalFirstProperties;
import com.wxx.aidocumentagent.chunking.TextChunk;
import com.wxx.aidocumentagent.chunking.infrastructure.persistence.DocumentChunk;
import com.wxx.aidocumentagent.chunking.infrastructure.persistence.DocumentChunkRepository;
import com.wxx.aidocumentagent.common.api.BusinessException;
import com.wxx.aidocumentagent.conversation.domain.ConversationAgentMode;
import com.wxx.aidocumentagent.conversation.domain.ConversationErrorCode;
import com.wxx.aidocumentagent.conversation.infrastructure.persistence.ConversationRepository;
import com.wxx.aidocumentagent.document.infrastructure.persistence.Document;
import com.wxx.aidocumentagent.document.infrastructure.persistence.DocumentRepository;
import com.wxx.aidocumentagent.knowledgebase.infrastructure.persistence.KnowledgeBaseRepository;
import com.wxx.aidocumentagent.rag.RagProperties;
import com.wxx.aidocumentagent.retrieval.RetrievalChannel;
import com.wxx.aidocumentagent.retrieval.RetrievalResult;
import com.wxx.aidocumentagent.retrieval.RetrievedChunk;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RetrievalFirstAgentTest {

    private static final long KB = 7L;
    private static final long DOC = 101L;
    private static final long CHUNK = 1001L;

    private KnowledgeBaseRepository knowledgeBases;
    private ConversationRepository conversations;
    private DocumentRepository documents;
    private DocumentChunkRepository chunks;
    private RagRetrievalGateway retrieval;
    private RagChatClient chat;
    private RetrievalQuestionRewriter rewriter;
    private RagModelCallAuditService audit;
    private RetrievalFirstProperties properties;
    private ExecutorService executor;
    private AgentRouter router;

    @BeforeEach
    void setUp() {
        knowledgeBases = mock(KnowledgeBaseRepository.class);
        conversations = mock(ConversationRepository.class);
        documents = mock(DocumentRepository.class);
        chunks = mock(DocumentChunkRepository.class);
        retrieval = mock(RagRetrievalGateway.class);
        chat = mock(RagChatClient.class);
        rewriter = mock(RetrievalQuestionRewriter.class);
        audit = mock(RagModelCallAuditService.class);
        properties = new RetrievalFirstProperties();
        executor = Executors.newVirtualThreadPerTaskExecutor();
        RagProperties rag = new RagProperties();
        rag.setContextWindowTokens(8192);
        rag.setReservedOutputTokens(1024);
        rag.setMinChunkCharacters(1);
        rag.setModel("test-model");
        when(knowledgeBases.existsById(KB)).thenReturn(true);
        when(conversations.existsByIdAndKnowledgeBaseIdAndAgentMode(42L, KB,
                ConversationAgentMode.RETRIEVAL_FIRST)).thenReturn(true);
        stubEvidence();
        RetrievalFirstAgent agent = new RetrievalFirstAgent(knowledgeBases, conversations, retrieval,
                new RagContextBuilder(documents, chunks, rag), chat, rewriter, new CitationValidator(), audit, rag,
                properties, executor);
        router = new AgentRouter(List.of(agent));
    }

    @AfterEach
    void tearDown() {
        executor.close();
    }

    @Test
    void 单轮先检索再生成且引用来自当前知识库的真实chunk() {
        when(retrieval.retrieve(KB, "系统如何去重？", 8, true)).thenReturn(hits(hit(KB, 0.03)));
        when(chat.complete(any(), any())).thenReturn(completion("通过业务幂等键去重。[C1]"));
        List<AgentEvent> events = new ArrayList<>();
        AgentRequest request = request("系统如何去重？", null);

        AgentResult result = router.execute(request, context(request, events));

        assertThat(result.answer()).isEqualTo("通过业务幂等键去重。[C1]");
        assertThat(result.citations()).singleElement().satisfies(citation -> {
            assertThat(citation.knowledgeBaseId()).isEqualTo(KB);
            assertThat(citation.documentId()).isEqualTo(DOC);
            assertThat(citation.chunkId()).isEqualTo(CHUNK);
            assertThat(citation.documentName()).isEqualTo("design.pdf");
            assertThat(citation.quote()).isEqualTo("重复消息通过业务幂等键去重。");
        });
        assertThat(result.toolCalls()).isEmpty();
        assertThat(result.modelName()).isEqualTo("test-model");
        assertThat(events).extracting(AgentEvent::kind).containsExactly(AgentEvent.Kind.STARTED,
                AgentEvent.Kind.RETRIEVING, AgentEvent.Kind.GENERATING, AgentEvent.Kind.COMPLETED);
        verify(audit).recordSuccess(eq(KB), eq("trace-7"), any(), any());
    }

    @Test
    void 会话必须在当前知识库且为检索优先模式() {
        AgentRequest request = request("问题", 99L);

        assertThatThrownBy(() -> router.execute(request, context(request, new ArrayList<>())))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getErrorCode())
                                .isEqualTo(ConversationErrorCode.CONVERSATION_NOT_FOUND));
        verify(conversations).existsByIdAndKnowledgeBaseIdAndAgentMode(99L, KB,
                ConversationAgentMode.RETRIEVAL_FIRST);
        verify(retrieval, never()).retrieve(eq(KB), any(), eq(8), eq(true));
    }

    @Test
    void 多轮指代只接受来自有界历史且保留原问题的检索改写() {
        List<AgentMemoryMessage> memory = List.of(
                new AgentMemoryMessage(1, AgentMemoryMessage.Role.USER, "产品A的设计是什么？"),
                new AgentMemoryMessage(2, AgentMemoryMessage.Role.ASSISTANT, "产品A使用业务幂等键。"));
        when(rewriter.rewrite(eq("它的限制？"), any())).thenReturn(completion("产品A；它的限制？"));
        when(retrieval.retrieve(KB, "产品A；它的限制？", 8, true)).thenReturn(hits(hit(KB, 0.03)));
        when(chat.complete(any(), any())).thenReturn(completion("需要业务幂等键。[C1]"));
        AgentRequest request = request("它的限制？", 42L);

        AgentResult result = router.execute(request, context(request, memory, new ArrayList<>(), Duration.ofSeconds(5)));

        assertThat(result.answer()).contains("[C1]");
        assertThat(result.usage().modelCalls()).isEqualTo(2);
        org.mockito.ArgumentCaptor<String> prompt = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(chat).complete(any(), prompt.capture());
        assertThat(prompt.getValue()).contains("当前用户问题：\n它的限制？", "BEGIN_UNTRUSTED_CONVERSATION_HISTORY");
    }

    @Test
    void 改写模型超时时保留原问题并用用户话题补全检索() {
        when(rewriter.rewrite(eq("它的限制？"), any())).thenThrow(new RagChatException(
                RagChatException.Reason.TIMEOUT, null));
        when(retrieval.retrieve(KB, "产品A；它的限制？", 8, true)).thenReturn(hits(hit(KB, 0.03)));
        when(chat.complete(any(), any())).thenReturn(completion("回答。[C1]"));
        AgentRequest request = request("它的限制？", 42L);
        List<AgentMemoryMessage> memory = List.of(new AgentMemoryMessage(1, AgentMemoryMessage.Role.USER, "产品A"));

        AgentResult result = router.execute(request, context(request, memory, new ArrayList<>(), Duration.ofSeconds(5)));

        assertThat(result.answer()).contains("[C1]");
        verify(audit).recordFailure(eq(KB), eq("trace-7"), eq("test-model"), eq(null), any(),
                eq("REWRITE_FAILED"));
    }

    @Test
    void 改写删改用户核心问题时拒绝候选且保留用户话题() {
        when(rewriter.rewrite(eq("它的限制？"), any())).thenReturn(completion("产品A有哪些优点？"));
        when(retrieval.retrieve(KB, "产品A；它的限制？", 8, true)).thenReturn(hits(hit(KB, 0.03)));
        when(chat.complete(any(), any())).thenReturn(completion("回答。[C1]"));
        AgentRequest request = request("它的限制？", 42L);
        List<AgentMemoryMessage> memory = List.of(new AgentMemoryMessage(1, AgentMemoryMessage.Role.USER, "产品A"));

        AgentResult result = router.execute(request,
                context(request, memory, new ArrayList<>(), Duration.ofSeconds(5)));

        assertThat(result.answer()).contains("[C1]");
        verify(retrieval).retrieve(KB, "产品A；它的限制？", 8, true);
    }

    @Test
    void 无结果低分或跨库命中均返回证据不足且不调用模型() {
        when(retrieval.retrieve(eq(KB), any(), eq(8), eq(true))).thenReturn(hits(),
                hits(hit(KB, 0.001)), hits(hit(99, 0.03)));
        for (int i = 0; i < 3; i++) {
            AgentRequest request = request("无资料问题" + i, null);
            AgentResult result = router.execute(request, context(request, new ArrayList<>()));
            assertThat(result.answer()).contains("不足以回答");
            assertThat(result.citations()).isEmpty();
        }
        verify(chat, never()).complete(any(), any());
        verify(chunks, never()).findByKnowledgeBaseIdAndIdIn(eq(99L), anyCollection());
    }

    @Test
    void 非法引用整段拒绝并不进入最终答案() {
        when(retrieval.retrieve(KB, "问题", 8, true)).thenReturn(hits(hit(KB, 0.03)));
        when(chat.complete(any(), any())).thenReturn(completion("凭空声称。[C999]"));
        AgentRequest request = request("问题", null);

        AgentResult result = router.execute(request, context(request, new ArrayList<>()));

        assertThat(result.answer()).contains("不足以生成可核验").doesNotContain("C999");
        assertThat(result.citations()).isEmpty();
        verify(audit).recordFailure(eq(KB), eq("trace-7"), eq("test-model"), any(), any(),
                eq("INVALID_CITATION"));
    }

    @Test
    void unreadableReadyDocumentStopsBeforeAnswerGeneration() {
        when(retrieval.retrieve(KB, "贾宝玉是谁？", 8, true)).thenReturn(hits(hit(KB, 0.03)));
        var broken = DocumentChunk.create(KB, DOC, new TextChunk(0,
                "\u0002\u0003\u0004\n".repeat(50), 10, 2, 67, null, Map.of()));
        ReflectionTestUtils.setField(broken, "id", CHUNK);
        when(chunks.findByKnowledgeBaseIdAndIdIn(eq(KB), anyCollection())).thenReturn(List.of(broken));
        var request = request("贾宝玉是谁？", null);

        assertThatThrownBy(() -> router.execute(request, context(request, new ArrayList<>())))
                .isInstanceOfSatisfying(BusinessException.class, failure -> assertThat(failure.getErrorCode())
                        .isEqualTo(com.wxx.aidocumentagent.rag.domain.RagErrorCode.UNREADABLE_EVIDENCE));
        verify(chat, never()).complete(any(), any());
    }

    @Test
    void 模型超时发布失败事件且不返回模型正文() {
        when(retrieval.retrieve(KB, "问题", 8, true)).thenReturn(hits(hit(KB, 0.03)));
        when(chat.complete(any(), any())).thenThrow(new RagChatException(RagChatException.Reason.TIMEOUT,
                new IllegalStateException("secret")));
        AgentRequest request = request("问题", null);
        List<AgentEvent> events = new ArrayList<>();

        assertThatThrownBy(() -> router.execute(request, context(request, events)))
                .isInstanceOfSatisfying(BusinessException.class, exception -> {
                    assertThat(exception.getErrorCode()).isEqualTo(AgentErrorCode.MODEL_TIMEOUT);
                    assertThat(exception.getMessage()).doesNotContain("secret");
                });
        assertThat(events).extracting(AgentEvent::kind).containsExactly(AgentEvent.Kind.STARTED,
                AgentEvent.Kind.RETRIEVING, AgentEvent.Kind.GENERATING, AgentEvent.Kind.FAILED);
    }

    @Test
    void 总截止时间取消慢检索且不进入生成() {
        when(retrieval.retrieve(eq(KB), any(), eq(8), eq(true))).thenAnswer(invocation -> {
            Thread.sleep(1500);
            return hits(hit(KB, 0.03));
        });
        AgentRequest request = request("问题", null);
        List<AgentEvent> events = new ArrayList<>();

        assertThatThrownBy(() -> router.execute(request, context(request, List.of(), events, Duration.ofMillis(500))))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getErrorCode()).isEqualTo(AgentErrorCode.DEADLINE_EXCEEDED));
        verify(chat, never()).complete(any(), any());
        assertThat(events).extracting(AgentEvent::kind).contains(AgentEvent.Kind.FAILED);
    }

    private AgentRequest request(String question, Long conversationId) {
        return new AgentRequest(question, KB, conversationId, AgentMode.RETRIEVAL_FIRST, Map.of());
    }

    @Test
    void structuredAnswerUsesTwoCallsAndServerRenderedVerifiedBindings() {
        properties.setStructuredAnswers(true);
        when(retrieval.retrieve(eq(KB), any(), eq(8), eq(true))).thenReturn(hits(hit(KB, .03)));
        when(chat.complete(any(), any())).thenReturn(completion("{\"status\":\"ANSWERED\",\"claims\":[{\"text\":\"通过业务幂等键去重。\",\"supports\":[{\"citationId\":\"C1\",\"quote\":\"重复消息通过业务幂等键去重。\"}]}],\"missing\":[]}"),
                completion("{\"status\":\"ANSWERED\",\"claims\":[{\"text\":\"通过业务幂等键去重。\",\"supports\":[{\"citationId\":\"C1\",\"quote\":\"重复消息通过业务幂等键去重。\"}]}],\"missing\":[]}"));
        var request = request("如何去重？", null);
        var result = router.execute(request, context(request, new ArrayList<>()));
        assertThat(result.answer()).isEqualTo("通过业务幂等键去重。 [C1]");
        assertThat(result.usage().modelCalls()).isEqualTo(2);
        assertThat(result.citations()).singleElement().satisfies(citation -> assertThat(citation.chunkId()).isEqualTo(CHUNK));
    }

    @Test
    void 评测观察获得本次提供给模型的真实证据而非索引正文() {
        when(retrieval.retrieve(eq(KB), any(), eq(8), eq(true))).thenReturn(hits(hit(KB, 0.03)));
        when(chat.complete(any(), any())).thenReturn(completion("业务幂等键去重 [C1]。"));
        List<String> observedText = new ArrayList<>();
        AgentRequest request = request("如何去重？", null);
        var probe = new com.wxx.aidocumentagent.agent.AgentExecutionProbe() {
            @Override public void evidence(com.wxx.aidocumentagent.agent.AgentCitation citation, String text) {
                assertThat(citation.knowledgeBaseId()).isEqualTo(KB);
                observedText.add(text);
            }
        };
        var context = AgentExecutionContext.forObservedRequest(request, "observed-rf", java.time.Instant.now().plusSeconds(5),
                AgentConversationContext.emptyFor(request), event -> {}, new AgentCancellationToken(), probe);
        var result = router.execute(request, context);
        assertThat(result.status()).isEqualTo(AgentResult.Status.COMPLETED);
        assertThat(observedText).containsExactly("重复消息通过业务幂等键去重。");
    }

    private AgentExecutionContext context(AgentRequest request, List<AgentEvent> events) {
        return context(request, List.of(), events, Duration.ofSeconds(5));
    }

    @Test
    void missingFactGetsOneBoundedSupplementAndKeepsExistingCitationIdsWithinTwoModelCalls() {
        properties.setStructuredAnswers(true);
        var old = DocumentChunk.create(KB, DOC, new TextChunk(0, "重复消息通过业务幂等键去重。", 10, 5, 5, null, Map.of()));
        ReflectionTestUtils.setField(old, "id", CHUNK);
        var next = DocumentChunk.create(KB, DOC, new TextChunk(1, "请求在20秒后超时。", 10, 5, 5, null, Map.of()));
        ReflectionTestUtils.setField(next, "id", 1002L);
        when(chunks.findByKnowledgeBaseIdAndIdIn(eq(KB), anyCollection())).thenReturn(List.of(old, next));
        when(retrieval.retrieve(eq(KB), any(), eq(8), eq(true))).thenReturn(hits(hit(KB, .03)));
        when(retrieval.retrieve(KB, "timeout", 8, true)).thenReturn(hits(new RetrievedChunk(1002, KB, DOC, "index hint", 5, 5,
                1, .9, null, null, .03, List.of(RetrievalChannel.VECTOR))));
        when(chat.complete(any(), any())).thenReturn(completion("{\"status\":\"PARTIAL\",\"claims\":[{\"text\":\"业务幂等键去重。\",\"supports\":[{\"citationId\":\"C1\",\"quote\":\"重复消息通过业务幂等键去重。\"}]}],\"missing\":[\"timeout\"]}"),
                completion("{\"status\":\"ANSWERED\",\"claims\":[{\"text\":\"业务幂等键去重。\",\"supports\":[{\"citationId\":\"C1\",\"quote\":\"重复消息通过业务幂等键去重。\"}]},{\"text\":\"20秒后超时。\",\"supports\":[{\"citationId\":\"C2\",\"quote\":\"请求在20秒后超时。\"}]}],\"missing\":[]}"));
        var request = request("如何去重，多久超时？", null);
        var result = router.execute(request, context(request, new ArrayList<>()));
        assertThat(result.status()).isEqualTo(AgentResult.Status.COMPLETED);
        assertThat(result.citations()).extracting(c -> c.citationId()).containsExactly("C1", "C2");
        assertThat(result.usage().modelCalls()).isEqualTo(2);
        verify(retrieval).retrieve(KB, "timeout", 8, true);
    }

    private AgentExecutionContext context(AgentRequest request, List<AgentMemoryMessage> memory,
                                          List<AgentEvent> events, Duration duration) {
        return AgentExecutionContext.forRequest(request, "trace-7", Instant.now().plus(duration),
                new AgentConversationContext(KB, request.conversationId(), memory), events::add,
                new AgentCancellationToken());
    }

    private RagChatCompletion completion(String answer) {
        return new RagChatCompletion(answer, "test-model", 11, 7, 18);
    }

    private RetrievalResult hits(RetrievedChunk... hits) {
        return new RetrievalResult(List.of(hits), false, List.of());
    }

    private RetrievedChunk hit(long knowledgeBaseId, double score) {
        return new RetrievedChunk(CHUNK, knowledgeBaseId, DOC, "索引文本", 5, 5, 1, 0.9,
                null, null, score, List.of(RetrievalChannel.VECTOR));
    }

    private void stubEvidence() {
        Document document = Document.uploaded(KB, "design.pdf", "storage-key", "text/plain", "txt", 1L,
                "a".repeat(64));
        ReflectionTestUtils.setField(document, "id", DOC);
        ReflectionTestUtils.setField(document, "status", com.wxx.aidocumentagent.document.domain.DocumentStatus.READY);
        DocumentChunk chunk = DocumentChunk.create(KB, DOC, new TextChunk(0,
                "重复消息通过业务幂等键去重。", 10, 5, 5, null, Map.of()));
        ReflectionTestUtils.setField(chunk, "id", CHUNK);
        when(chunks.findByKnowledgeBaseIdAndIdIn(eq(KB), anyCollection())).thenReturn(List.of(chunk));
        when(documents.findByKnowledgeBaseIdAndIdIn(eq(KB), anyCollection())).thenReturn(List.of(document));
    }
}
