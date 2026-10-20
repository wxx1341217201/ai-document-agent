package com.wxx.aidocumentagent.reading;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import com.wxx.aidocumentagent.chunking.infrastructure.persistence.DocumentChunkRepository;
import com.wxx.aidocumentagent.document.infrastructure.persistence.DocumentRepository;
import com.wxx.aidocumentagent.rag.infrastructure.chat.RagChatOptions;
import com.wxx.aidocumentagent.retrieval.HybridRetriever;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.ObjectMapper;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = {"app.rag.enabled", "app.reading.enabled"}, havingValue = "true", matchIfMissing = true)
public class ReadingConfiguration {
    @Bean(name = "readingCalls", destroyMethod = "close") ExecutorService readingCalls() { return Executors.newVirtualThreadPerTaskExecutor(); }
    @Bean(name = "readingWorkerExecutor", destroyMethod = "close") ExecutorService readingWorkerExecutor() { return Executors.newSingleThreadExecutor(); }
    @Bean ReadingSources readingSources(DocumentRepository docs, DocumentChunkRepository chunks, HybridRetriever retrieval,
                                       @org.springframework.beans.factory.annotation.Value("${app.reading.rerank-enabled:false}") boolean rerank) {
        return new ReadingSources(docs, chunks, retrieval, rerank);
    }
    @Bean ReadingModel readingModel(@Qualifier("ragSpringAiChatClient") ChatClient client, RagChatOptions options) {
        return new SpringAiReadingModel(client, options);
    }
    @Bean ReadingStore readingStore(ReadingTaskRepository tasks, ReadingResultRepository results, ReadingNoteRepository notes,
                                    ReadingNoteRevisionRepository revisions, ReadingDispatchLockRepository dispatch, ObjectMapper mapper) {
        return new ReadingStore(tasks, results, notes, revisions, dispatch, mapper);
    }
    @Bean ReadingService readingService(ReadingStore store, ReadingSources sources, ObjectMapper mapper) { return new ReadingService(store, sources, mapper); }
    @Bean ReadingCoordinator readingCoordinator(ReadingModel model, ReadingSources sources, ObjectMapper mapper) {
        return new ReadingCoordinator(model, sources, mapper);
    }
    @Bean ReadingWorker readingWorker(ReadingStore store, ReadingSources sources, ReadingCoordinator coordinator,
                                      @Qualifier("readingWorkerExecutor") ExecutorService worker, @Qualifier("readingCalls") ExecutorService calls) {
        return new ReadingWorker(store, sources, coordinator, worker, calls);
    }
}
