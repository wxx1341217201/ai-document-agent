package com.wxx.aidocumentagent.reading;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import javax.sql.DataSource;
import jakarta.persistence.EntityManagerFactory;
import com.wxx.aidocumentagent.common.api.BusinessException;
import com.wxx.aidocumentagent.reading.ReadingState.*;
import org.hibernate.resource.jdbc.spi.StatementInspector;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;

/** Real MySQL, restricted throwaway schema. No model calls and no access to the business database. */
@SpringJUnitConfig(ReadingPersistenceTest.Config.class)
@EnabledIfEnvironmentVariable(named = "READING_TEST_CONFIG", matches = ".+")
class ReadingPersistenceTest {
    @Autowired ReadingStore store;
    @Autowired DataSource dataSource;
    @Autowired Sql sql;
    @BeforeEach void clean() { new JdbcTemplate(dataSource).update("DELETE FROM reading_task"); sql.statements.clear(); }

    @Test void persistsCheckpointsAndSeparatesResumeFromFreshRound() {
        var task = create("persist"); var run = store.claim(); assertThat(run).isNotNull();
        assertThat(store.claim()).isNull();
        run.state().modelCalls = 3; run.state().toolCalls = 2;
        run.state().cursors.put(15L, new Cursor(1, 0, 1)); addFinding(run.state());
        store.checkpoint(run, run.state());
        assertThat(store.inspect(task.id(), 8).state().cursors.get(15L).reviewedChunks()).isEqualTo(1);
        store.finish(run, run.state(), true);
        var interrupted = store.get(task.id(), 8);
        store.action(task.id(), 8, interrupted.version(), 1, "resume");
        var resumed = store.claim();
        assertThat(resumed.deadline()).isEqualTo(run.deadline());
        assertThat(resumed.state().modelCalls).isEqualTo(3);
        assertThat(resumed.state().findings).hasSize(1);
        store.finish(resumed, resumed.state(), false);
        var delivered = store.get(task.id(), 8);
        var continued = store.action(task.id(), 8, delivered.version(), 1, "continue");
        assertThat(continued.roundNo()).isEqualTo(2);
        assertThat(continued.usage().modelCalls()).isZero();
        assertThat(store.inspect(task.id(), 8).state().findings).hasSize(1);
    }

    @Test void preservesManualNoteAndSourcesWhenNewAiResultArrives() {
        var task = create("note"); var note = store.note(task.id(), 8);
        var edited = store.edit(task.id(), 8, new ReadingApi.Edit("我的独立分析", note.version(), null));
        assertThat(edited.version()).isEqualTo(note.version() + 1);
        var run = store.claim(); addFinding(run.state()); store.finish(run, run.state(), false);
        var after = store.note(task.id(), 8);
        assertThat(after.content()).isEqualTo("我的独立分析"); assertThat(after.pendingResultId()).isNotNull();
        var mixed = store.edit(task.id(), 8, new ReadingApi.Edit("我的独立分析\n\n新证据 [C1]", after.version(), after.pendingResultId()));
        assertThat(mixed.sources()).extracting(Source::citationId).containsExactly("C1");
        var history = store.noteHistory(task.id(), 8, 0, 20);
        var old = store.revision(task.id(), 8, history.content().getFirst().id());
        assertThat(old.content()).isEqualTo("我的独立分析");
    }

    @Test void fencesOldWorkerAndRejectsConflictingEditsAndActions() {
        var task = create("fence"); var old = store.claim(); old.state().modelCalls = 2;
        store.checkpoint(old, old.state()); store.finish(old, old.state(), true);
        var latest = store.get(task.id(), 8);
        store.action(task.id(), 8, latest.version(), 1, "resume"); var current = store.claim();
        assertThat(current.token()).isNotEqualTo(old.token());
        assertThatThrownBy(() -> store.checkpoint(old, old.state())).isInstanceOf(BusinessException.class);
        var note = store.note(task.id(), 8);
        store.edit(task.id(), 8, new ReadingApi.Edit("已保存", note.version(), null));
        assertThatThrownBy(() -> store.edit(task.id(), 8, new ReadingApi.Edit("过期覆盖", note.version(), null)))
                .isInstanceOf(BusinessException.class);
        // Progress can change the optimistic version; cancellation still targets this exact round.
        store.action(task.id(), 8, 0, 1, "cancel");
        assertThat(store.get(task.id(), 8).status()).isEqualTo("CANCELLED");
        assertThatThrownBy(() -> store.finish(current, current.state(), false)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> store.action(task.id(), 8, store.get(task.id(), 8).version(), 99, "continue"))
                .isInstanceOf(BusinessException.class);
    }

    @Test void idempotentCreateAndScopeGuardsDoNotLeakResult() {
        var state = new ReadingState(); state.goals.add(new Goal("G0", "目标", "查询", "RETRIEVAL_FIRST", false));
        var request = new ReadingApi.Create(8, "目标", List.of(15L), false, null);
        var first = store.create("same-key", "a".repeat(64), request, scope(), state, null);
        var again = store.create("same-key", "a".repeat(64), request, scope(), state, null);
        assertThat(again.id()).isEqualTo(first.id());
        assertThatThrownBy(() -> store.create("same-key", "b".repeat(64), request, scope(), state, null))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> store.get(first.id(), 9)).isInstanceOf(BusinessException.class);
        var run = store.claim(); addFinding(run.state()); store.finish(run, run.state(), false);
        var result = store.get(first.id(), 8).latestResultId();
        assertThatThrownBy(() -> store.result(first.id(), 9, result)).isInstanceOf(BusinessException.class);
        assertThat(store.list(8, 0, 20).totalElements()).isEqualTo(1);
    }

    @Test void listsUseClosedSummaryProjectionAndDoNotReadCheckpointOrNoteBodies() {
        create("projection"); sql.statements.clear();
        assertThat(store.list(8, 0, 5).content()).hasSize(1);
        assertThat(sql.statements).isNotEmpty().allSatisfy(statement -> {
            assertThat(statement).doesNotContain("checkpoint_json", "scope_json", "payload_json", "sources_json");
        });
    }

    @Test void explicitExpansionKeepsOriginalGoalAndOldResult() {
        var task = create("expand"); var run = store.claim(); addFinding(run.state()); store.finish(run, run.state(), false);
        var before = store.get(task.id(), 8);
        var expanded = store.expand(task.id(), 8, before.version(), List.of(scope().getFirst(),
                new DocumentRef(16, "补充资料.txt", "c".repeat(64), "d".repeat(64), 1)));
        assertThat(expanded.roundNo()).isEqualTo(2); assertThat(expanded.documents()).hasSize(2);
        assertThat(expanded.goals()).extracting(Goal::id).containsExactly("G0");
        assertThat(store.result(task.id(), 8, before.latestResultId()).documents()).hasSize(1);
        assertThat(store.note(task.id(), 8).content()).contains("测试结论");
    }

    @Test void workerToPersistentResultAndEditableNoteRunsAsOneCompleteWorkflow() {
        var task = create("workflow"); var run = store.claim();
        var source = new Source(null, 15, "测试.txt", "a".repeat(64), 100, 0,
                com.wxx.aidocumentagent.agent.ClaimEvidenceValidator.sha256("受控流程原文"), 0, "受控流程原文", null, null, null);
        var sourceAccess = org.mockito.Mockito.mock(ReadingSources.class);
        org.mockito.Mockito.when(sourceAccess.snapshot(org.mockito.ArgumentMatchers.eq(8L), org.mockito.ArgumentMatchers.anySet())).thenReturn(scope());
        org.mockito.Mockito.when(sourceAccess.search(org.mockito.ArgumentMatchers.eq(8L), org.mockito.ArgumentMatchers.anyList(), org.mockito.ArgumentMatchers.anyString())).thenReturn(List.of(source));
        ReadingModel model = (phase, system, user) -> new ReadingModel.Reply(switch (phase) {
            case "ANALYZE" -> "{\"findings\":[{\"goalId\":\"G0\",\"kind\":\"FACT\",\"text\":\"这是受控流程原文。\",\"supports\":[{\"citationId\":\"C1\",\"quote\":\"受控流程原文\"}]}],\"missing\":[]}";
            case "REFLECT" -> "{\"acceptedFindingIds\":[\"F1\"],\"retractedFindingIds\":[],\"coveredGoalIds\":[\"G0\"],\"reviewedSourceIds\":[],\"missing\":[]}";
            default -> throw new AssertionError(phase);
        }, 10, 20);
        try (var workers = java.util.concurrent.Executors.newSingleThreadExecutor(); var calls = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            new ReadingWorker(store, sourceAccess, new ReadingCoordinator(model, sourceAccess, new JsonMapper()), workers, calls).execute(run);
        }
        var done = store.get(task.id(), 8); assertThat(done.status()).isEqualTo("SUCCEEDED"); assertThat(done.completion()).isEqualTo("FULL");
        assertThat(done.usage().modelCalls()).isEqualTo(2); assertThat(done.usage().toolCalls()).isEqualTo(3);
        var result = store.result(task.id(), 8, done.latestResultId());
        assertThat(result.markdown()).contains("受控流程原文", "[C1]");
        var note = store.note(task.id(), 8); assertThat(note.sources()).hasSize(1);
        var edited = store.edit(task.id(), 8, new ReadingApi.Edit("自己的理解 [C1]", note.version(), null));
        assertThat(ReadingRendering.export(edited)).contains("自己的理解 [C1]", "受控流程原文");
        assertThat(store.result(task.id(), 8, done.latestResultId()).markdown()).isEqualTo(result.markdown());
    }

    @Test void changedSourcesCanReopenAFullTaskWhilePreservingHistoricalNoteAndResult() {
        var task = create("refresh"); var run = store.claim(); addFinding(run.state());
        run.state().goals = List.of(new Goal("G0", "目标", "查询", "RETRIEVAL_FIRST", true));
        store.finish(run, run.state(), false); var before = store.get(task.id(), 8);
        assertThat(before.completion()).isEqualTo("FULL");
        var oldNote = store.note(task.id(), 8);
        var changed = List.of(new DocumentRef(15, "测试.txt", "c".repeat(64), "d".repeat(64), 3));
        var refreshed = store.refresh(task.id(), 8, before.version(), before.roundNo(), changed);
        assertThat(refreshed.status()).isEqualTo("PENDING"); assertThat(refreshed.roundNo()).isEqualTo(2);
        assertThat(refreshed.completion()).isEqualTo("NONE");
        assertThat(store.inspect(task.id(), 8).state().findings).isEmpty();
        assertThat(store.note(task.id(), 8).content()).isEqualTo(oldNote.content());
        assertThat(store.note(task.id(), 8).sources()).isEqualTo(oldNote.sources());
        assertThat(store.result(task.id(), 8, before.latestResultId()).completion()).isEqualTo("FULL");
    }

    @Test void jsonNumbersDoNotCausePhantomVersionsOrUpdatesOfImmutableResults() {
        var created = create("stable-json");
        assertThat(store.get(created.id(), 8).version()).isEqualTo(created.version());
        var run = store.claim(); addFinding(run.state()); sql.statements.clear(); store.finish(run, run.state(), false);
        var note = store.note(created.id(), 8);
        assertThat(note.version()).isEqualTo(1);
        var edited = store.edit(created.id(), 8, new ReadingApi.Edit("人工编辑 [C1]", note.version(), null));
        assertThat(edited.version()).isEqualTo(note.version() + 1);
        assertThat(store.note(created.id(), 8).version()).isEqualTo(edited.version());
        assertThat(sql.statements).noneMatch(s -> s.toLowerCase(java.util.Locale.ROOT).startsWith("update reading_result"));
    }

    private ReadingApi.Task create(String suffix) {
        var state = new ReadingState(); state.goals.add(new Goal("G0", "目标", "查询", "RETRIEVAL_FIRST", false));
        return store.create(suffix + UUID.randomUUID(), "a".repeat(64), new ReadingApi.Create(8, "目标", List.of(15L), false, null), scope(), state, null);
    }
    private List<DocumentRef> scope() { return List.of(new DocumentRef(15, "测试.txt", "b".repeat(64), "a".repeat(64), 2)); }
    private void addFinding(ReadingState state) {
        state.sources.add(new Source("C1", 15, "测试.txt", "a".repeat(64), 100, 0, "e".repeat(64), 0, "测试原文", null, null, null));
        state.findings.add(new Finding("F1", "G0", "FACT", "测试结论", List.of(new Support("C1", "测试原文"))));
    }

    static class Sql implements StatementInspector {
        final List<String> statements = new CopyOnWriteArrayList<>();
        public String inspect(String query) { statements.add(query); return query; }
    }
    @Configuration(proxyBeanMethods = false)
    @EnableTransactionManagement
    @EnableJpaRepositories(basePackageClasses = ReadingTaskRepository.class)
    static class Config {
        @Bean Sql sql() { return new Sql(); }
        @Bean JsonMapper mapper() { return new JsonMapper(); }
        @Bean DataSource dataSource(ObjectMapper mapper) throws Exception {
            var config = mapper.readTree(Files.readString(Path.of(System.getenv("READING_TEST_CONFIG"))));
            String url = config.get("url").asText();
            if (!url.matches("jdbc:mysql://127\\.0\\.0\\.1:3307/aida_reading_test_[a-f0-9]{10}\\?.*"))
                throw new IllegalStateException("Only isolated local reading test schemas are allowed");
            var ds = new DriverManagerDataSource(url, config.get("user").asText(), config.get("password").asText());
            var jdbc = new JdbcTemplate(ds);
            jdbc.execute("CREATE TABLE IF NOT EXISTS knowledge_base(id BIGINT NOT NULL PRIMARY KEY)");
            jdbc.update("INSERT IGNORE INTO knowledge_base(id) VALUES(8),(9)");
            if (jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name='reading_task'", Integer.class) == 0)
                new ResourceDatabasePopulator(new ClassPathResource("db/migration/V17__create_reading_workspace.sql")).execute(ds);
            return ds;
        }
        @Bean LocalContainerEntityManagerFactoryBean entityManagerFactory(DataSource ds, Sql sql, JsonMapper mapper) {
            var factory = new LocalContainerEntityManagerFactoryBean(); factory.setDataSource(ds);
            factory.setPackagesToScan("com.wxx.aidocumentagent.reading"); factory.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
            var props = new Properties(); props.put("hibernate.hbm2ddl.auto", "validate");
            props.put("hibernate.type.json_format_mapper", new org.hibernate.type.format.jackson.Jackson3JsonFormatMapper(mapper));
            props.put("hibernate.session_factory.statement_inspector", sql); props.put("hibernate.jdbc.time_zone", "UTC");
            factory.setJpaProperties(props); return factory;
        }
        @Bean PlatformTransactionManager transactionManager(EntityManagerFactory factory) { return new JpaTransactionManager(factory); }
        @Bean ReadingStore store(ReadingTaskRepository tasks, ReadingResultRepository results, ReadingNoteRepository notes,
                                  ReadingNoteRevisionRepository revisions, ReadingDispatchLockRepository lock, ObjectMapper mapper) {
            return new ReadingStore(tasks, results, notes, revisions, lock, mapper);
        }
    }
}
