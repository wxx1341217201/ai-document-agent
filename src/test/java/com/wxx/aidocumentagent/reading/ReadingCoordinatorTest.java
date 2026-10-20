package com.wxx.aidocumentagent.reading;

import java.time.*;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import com.wxx.aidocumentagent.agent.ClaimEvidenceValidator;
import com.wxx.aidocumentagent.reading.ReadingState.*;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ReadingCoordinatorTest {
    private final JsonMapper mapper = new JsonMapper();
    private final ReadingSources sources = mock(ReadingSources.class);
    private static final String TEXT = "测试语料：镜子有两面。这是受控测试夹具，不是书籍金标。";
    private final Source first = new Source(null, 15, "测试文本.txt", "a".repeat(64), 100, 0,
            ClaimEvidenceValidator.sha256(TEXT), 0, TEXT, null, null, null);
    private final Source second = new Source(null, 15, "测试文本.txt", "a".repeat(64), 101, 1,
            ClaimEvidenceValidator.sha256(TEXT), 0, TEXT, null, null, null);
    private static final String PLAN = "{\"steps\":[{\"description\":\"分析主题\",\"query\":\"镜子\",\"engine\":\"REACT\"}]}";
    private static final String DRAFT = "{\"findings\":[{\"goalId\":\"G1\",\"kind\":\"FACT\",\"text\":\"原文写明镜子有两面。\",\"supports\":[{\"citationId\":\"C1\",\"quote\":\"镜子有两面\"}]}],\"missing\":[]}";

    @Test void relaysWithinOneTaskAndOnlyCommitsVerifiedFindings() {
        var decisions = new AtomicInteger();
        var model = (ReadingModel) (phase, system, user) -> reply(switch (phase) {
            case "PLAN" -> PLAN;
            case "DECIDE" -> decisions.incrementAndGet() == 1 ? "{\"action\":\"context\",\"chunkId\":100}" : "{\"action\":\"finish\"}";
            case "ANALYZE" -> DRAFT;
            case "REFLECT" -> checked("F1", "\"G0\",\"G1\"", "", "");
            default -> throw new AssertionError(phase);
        });
        when(sources.search(anyLong(), anyList(), anyString())).thenReturn(List.of(first));
        when(sources.context(anyLong(), anyList(), any(), eq(100L))).thenReturn(List.of(second));
        var run = run(false); execute(run, model);
        assertThat(run.state().completion(run.scope())).isEqualTo("FULL");
        assertThat(run.state().findings).hasSize(1);
        assertThat(run.state().stages).extracting(Stage::engine).contains("RETRIEVAL_FIRST", "REACT", "PLAN_EXECUTE_REFLECT");
        assertThat(run.state().pendingDraft).isNull();
        assertThat(run.state().modelCalls).isEqualTo(5);
        verify(sources).context(eq(8L), anyList(), any(), eq(100L));
    }

    @Test void replansOnlyOnceAndCannotDeleteOriginalUnsatisfiedGoal() {
        var checks = new AtomicInteger();
        ReadingModel model = (phase, system, user) -> reply(switch (phase) {
            case "PLAN" -> PLAN;
            case "DECIDE" -> "{\"action\":\"finish\"}";
            case "ANALYZE" -> DRAFT;
            case "REPLAN" -> "{\"queries\":[\"补查原目标\"]}";
            case "REFLECT" -> checked("F" + checks.incrementAndGet(), "\"G1\"", "", "\"原目标仍有缺口\"");
            default -> throw new AssertionError(phase);
        });
        when(sources.search(anyLong(), anyList(), anyString())).thenReturn(List.of(first));
        var run = run(false); execute(run, model);
        assertThat(run.state().replans).isEqualTo(1);
        assertThat(run.state().goals).filteredOn(g -> g.id().equals("G0")).singleElement().satisfies(g -> assertThat(g.covered()).isFalse());
        assertThat(run.state().findings).hasSize(1);
        assertThat(run.state().completion(run.scope())).isEqualTo("PARTIAL");
        assertThat(run.state().modelCalls).isLessThanOrEqualTo(8);
    }

    @Test void excludesFabricatedCitationFromSemanticCheckAndPublication() {
        ReadingModel model = (phase, system, user) -> reply(switch (phase) {
            case "PLAN" -> PLAN; case "DECIDE" -> "{\"action\":\"finish\"}";
            case "ANALYZE" -> DRAFT.replace("C1", "C999");
            case "REFLECT" -> {
                assertThat(mapper.readTree(user).get("candidates").isEmpty()).isTrue();
                assertThat(user).doesNotContain("C999");
                yield checked("", "\"G0\",\"G1\"", "", "").replace("[\"\"]", "[]");
            }
            case "REPLAN" -> "{\"queries\":[]}";
            default -> throw new AssertionError(phase);
        });
        when(sources.search(anyLong(), anyList(), anyString())).thenReturn(List.of(first));
        var run = run(false);
        execute(run, model);
        assertThat(run.state().findings).isEmpty(); assertThat(run.state().completion(run.scope())).isEqualTo("NONE");
        assertThat(run.state().gaps).anyMatch(g -> g.contains("原文引用未通过校验"));
        assertThat(run.state().stages).anyMatch(s -> s.operation().equals("quote_validation") && s.message().contains("UNKNOWN_CITATION"));
    }

    @Test void retainsValidFindingsWhenAnotherCandidateHasAnInexactQuote() {
        var run = run(false); run.state().planned = true; run.state().modelCalls = 7;
        run.state().goals.add(new Goal("G0", run.goal(), "目标", "RETRIEVAL_FIRST", false));
        run.state().goals.add(new Goal("G1", "分析主题", "镜子", "REACT", false));
        run.state().register(first); run.state().pendingSourceIds = List.of("C1");
        var draft = mapper.readTree(DRAFT);
        var bad = draft.get("findings").get(0).deepCopy();
        ((tools.jackson.databind.node.ObjectNode) bad.get("supports").get(0)).put("quote", "镜子只有一面");
        ((tools.jackson.databind.node.ArrayNode) draft.get("findings")).add(bad);
        run.state().pendingDraft = draft.toString();
        ReadingModel model = (phase, system, user) -> {
            assertThat(phase).isEqualTo("REFLECT");
            assertThat(mapper.readTree(user).get("candidates").size()).isEqualTo(1);
            assertThat(user).doesNotContain("镜子只有一面");
            return reply(checked("F1", "\"G0\",\"G1\"", "", ""));
        };
        execute(run, model);
        assertThat(run.state().findings).hasSize(1);
        assertThat(run.state().completion(run.scope())).isEqualTo("PARTIAL");
        assertThat(run.state().goals).noneMatch(Goal::covered);
        assertThat(run.state().gaps).anyMatch(g -> g.contains("G1") && g.contains("原文引用未通过校验"));
        assertThat(run.state().pendingDraft).isNull();
    }

    @Test void doesNotAdvanceCoverageForReadButUnreviewedUnits() {
        when(sources.read(anyLong(), anyList(), eq(15L), any())).thenReturn(new ReadingSources.ReadPage(List.of(first, second), new Cursor(2, 0, 2)));
        ReadingModel model = (phase, system, user) -> reply(switch (phase) {
            case "PLAN" -> PLAN; case "ANALYZE" -> DRAFT;
            case "REFLECT" -> checked("F1", "\"G0\",\"G1\"", "\"C1\"", "");
            default -> throw new AssertionError(phase);
        });
        var run = run(true); execute(run, model);
        assertThat(run.state().cursors).isEmpty();
        assertThat(run.state().completion(run.scope())).isEqualTo("PARTIAL");
    }

    @Test void advancesOnlyVerifiedReadCursorAndRequiresAllDocumentsForFull() {
        when(sources.read(anyLong(), anyList(), eq(15L), any())).thenReturn(new ReadingSources.ReadPage(List.of(first, second), new Cursor(2, 0, 2)));
        ReadingModel model = (phase, system, user) -> reply(switch (phase) {
            case "PLAN" -> PLAN; case "ANALYZE" -> DRAFT;
            case "REFLECT" -> checked("F1", "\"G0\",\"G1\"", "\"C1\",\"C2\"", "");
            default -> throw new AssertionError(phase);
        });
        var run = run(true); execute(run, model);
        assertThat(run.state().cursors.get(15L).reviewedChunks()).isEqualTo(2);
        assertThat(run.state().completion(run.scope())).isEqualTo("FULL");
        assertThat(run.state().completion(List.of(run.scope().getFirst(), new DocumentRef(16, "未阅读.txt", "b".repeat(64), "c".repeat(64), 1))))
                .isEqualTo("PARTIAL");
    }

    @Test void rejectsReplanSchemaThatTriesToRewriteGoalTree() {
        ReadingModel model = (phase, system, user) -> reply(switch (phase) {
            case "PLAN" -> PLAN; case "DECIDE" -> "{\"action\":\"finish\"}"; case "ANALYZE" -> DRAFT;
            case "REFLECT" -> checked("F1", "\"G1\"", "", "\"缺口\"");
            case "REPLAN" -> "{\"queries\":[],\"goals\":[]}";
            default -> throw new AssertionError(phase);
        });
        when(sources.search(anyLong(), anyList(), anyString())).thenReturn(List.of(first));
        var run = run(false);
        assertThatThrownBy(() -> execute(run, model)).isInstanceOf(ReadingBudget.Interrupted.class);
        assertThat(run.state().goals).extracting(Goal::id).containsExactly("G0", "G1");
    }

    @Test void resumesPendingVerificationWithoutRegeneratingAlreadySavedDraft() {
        var run = run(false); run.state().planned = true;
        run.state().goals.add(new Goal("G0", run.goal(), "目标", "RETRIEVAL_FIRST", false));
        run.state().goals.add(new Goal("G1", "分析主题", "镜子", "REACT", false));
        run.state().register(first); run.state().pendingSourceIds = List.of("C1"); run.state().pendingDraft = DRAFT;
        ReadingModel model = (phase, system, user) -> {
            assertThat(phase).isEqualTo("REFLECT"); return reply(checked("F1", "\"G0\",\"G1\"", "", ""));
        };
        execute(run, model);
        assertThat(run.state().modelCalls).isEqualTo(1); assertThat(run.state().completion(run.scope())).isEqualTo("FULL");
        verifyNoInteractions(sources);
    }

    @Test void allowsNewEvidenceToRetractAnEarlierInterpretation() {
        var run = run(false); run.state().planned = true;
        run.state().goals.add(new Goal("G0", run.goal(), "目标", "RETRIEVAL_FIRST", false));
        run.state().goals.add(new Goal("G1", "分析主题", "镜子", "REACT", false));
        run.state().register(first); run.state().findingSequence = 1;
        run.state().findings.add(new Finding("F1", "G1", "INFERENCE", "旧的解释", List.of(new Support("C1", "镜子有两面"))));
        when(sources.search(anyLong(), anyList(), anyString())).thenReturn(List.of(first));
        ReadingModel model = (phase, system, user) -> reply(switch (phase) {
            case "DECIDE" -> "{\"action\":\"finish\"}"; case "ANALYZE" -> DRAFT;
            case "REFLECT" -> checked("F2", "\"G0\",\"G1\"", "", "").replace("\"retractedFindingIds\":[]", "\"retractedFindingIds\":[\"F1\"]");
            default -> throw new AssertionError(phase);
        });
        execute(run, model);
        assertThat(run.state().findings).extracting(Finding::id).containsExactly("F2");
    }

    @Test void unverifiedNonTextCoverageCannotBecomeFullEvenWhenModelSaysCovered() {
        var run = run(true); var state = run.state();
        state.goals.add(new Goal("G0", run.goal(), "目标", "RETRIEVAL_FIRST", true));
        state.register(first); state.findings.add(new Finding("F1", "G0", "FACT", "有依据的事实", List.of(new Support("C1", "镜子有两面"))));
        state.cursors.put(15L, new Cursor(2, 0, 2)); state.materialGaps.add("图表完整性尚未核验");
        assertThat(state.completion(run.scope())).isEqualTo("PARTIAL");
        assertThat(ReadingRendering.gaps(state, run.scope())).contains("图表完整性尚未核验");
    }

    @Test void nonComprehensiveVerificationCannotSeeUnquotedSourceTextToFillMissingSupport() {
        var run = run(false); run.state().planned = true;
        run.state().goals.add(new Goal("G0", run.goal(), "目标", "RETRIEVAL_FIRST", false));
        run.state().goals.add(new Goal("G1", "分析主题", "镜子", "REACT", false));
        run.state().register(first); run.state().pendingSourceIds = List.of("C1"); run.state().pendingDraft = DRAFT;
        ReadingModel model = (phase, system, user) -> {
            assertThat(phase).isEqualTo("REFLECT");
            var supplied = mapper.readTree(user);
            assertThat(supplied.get("readingUnits").isEmpty()).isTrue();
            assertThat(user).contains("镜子有两面").doesNotContain("这是受控测试夹具，不是书籍金标");
            assertThat(system).contains("仅可使用它自己的 supports.quote");
            return reply(checked("F1", "\"G0\",\"G1\"", "", ""));
        };
        execute(run, model); assertThat(run.state().completion(run.scope())).isEqualTo("FULL");
    }

    @Test void unresolvedMissingItemsKeepOriginalGoalOpenEvenIfModelMarksItCovered() {
        var run = run(false); run.state().planned = true; run.state().modelCalls = 7;
        run.state().goals.add(new Goal("G0", run.goal(), "目标", "RETRIEVAL_FIRST", false));
        run.state().goals.add(new Goal("G1", "分析主题", "镜子", "REACT", false));
        run.state().register(first); run.state().pendingSourceIds = List.of("C1"); run.state().pendingDraft = DRAFT;
        ReadingModel model = (phase, system, user) -> reply(checked("F1", "\"G0\",\"G1\"", "", "\"仍缺少必需上下文\""));
        execute(run, model);
        assertThat(run.state().goals).filteredOn(g -> g.id().equals("G0")).singleElement().satisfies(g -> assertThat(g.covered()).isFalse());
        assertThat(run.state().completion(run.scope())).isEqualTo("PARTIAL");
    }

    private ReadingStore.Run run(boolean comprehensive) {
        var state = new ReadingState(); state.comprehensive = comprehensive;
        return new ReadingStore.Run("11111111-1111-4111-8111-111111111111", 8, "分析主题", 1, "token",
                Instant.now().plusSeconds(180), List.of(new DocumentRef(15, "测试文本.txt", "b".repeat(64), "a".repeat(64), 2)), state);
    }
    private void execute(ReadingStore.Run run, ReadingModel model) {
        try (var calls = Executors.newVirtualThreadPerTaskExecutor()) {
            var coordinator = new ReadingCoordinator(model, sources, mapper);
            var budget = new ReadingBudget(run.state(), run.deadline(), Clock.systemUTC(), () -> {}, calls);
            coordinator.execute(run, budget, () -> {});
        }
    }
    private static ReadingModel.Reply reply(String text) { return new ReadingModel.Reply(text, 10, 20); }
    private static String checked(String accepted, String goals, String reviewed, String missing) {
        return "{\"acceptedFindingIds\":[\"" + accepted + "\"],\"retractedFindingIds\":[],\"coveredGoalIds\":[" + goals
                + "],\"reviewedSourceIds\":[" + reviewed + "],\"missing\":[" + missing + "]}";
    }
}
