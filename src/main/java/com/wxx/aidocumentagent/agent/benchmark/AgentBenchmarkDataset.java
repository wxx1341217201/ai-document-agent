package com.wxx.aidocumentagent.agent.benchmark;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.wxx.aidocumentagent.agent.AgentExecutionProbe.ToolAction;
import com.wxx.aidocumentagent.agent.AgentMode;
import com.wxx.aidocumentagent.agent.AgentResult;
import com.wxx.aidocumentagent.rag.benchmark.RagAccuracyDataset;
import com.wxx.aidocumentagent.rag.benchmark.RagAccuracyDataset.RequiredFact;
import tools.jackson.databind.ObjectMapper;

public record AgentBenchmarkDataset(List<Task> tasks, long knowledgeBaseId) {
    static final Set<String> TOOLS = Set.of("searchKnowledgeBase", "getDocumentMetadata", "getChunkContext");
    static final Set<String> FORBIDDEN = Set.of("crossKnowledgeBaseAccess", "fabricatedCitation", "hiddenReasoning",
            "unboundedReplanning", "unboundedToolCalls", "rawErrorLeak");
    static final Set<String> TAGS = Set.of("SINGLE_DOCUMENT", "CROSS_DOCUMENT", "MULTI_TURN", "COREFERENCE",
            "MULTIPLE_TOOLS", "MULTI_STEP", "REFUSAL", "TOOL_FAILURE", "REPLAN");

    public AgentBenchmarkDataset {
        tasks = List.copyOf(tasks);
        if (tasks.isEmpty() || knowledgeBaseId <= 0 || tasks.stream().anyMatch(t -> t.knowledgeBaseId() != knowledgeBaseId)
                || tasks.stream().map(Task::caseId).distinct().count() != tasks.size()) {
            throw new IllegalArgumentException("Empty, duplicate or mixed-scope task dataset");
        }
    }

    public static AgentBenchmarkDataset load(Path path, ObjectMapper mapper) throws IOException {
        List<Task> tasks = new ArrayList<>();
        int lineNo = 0;
        for (String line : Files.readAllLines(path, StandardCharsets.UTF_8)) {
            lineNo++;
            if (line.isBlank()) continue;
            try { tasks.add(mapper.readValue(line, Task.class)); }
            catch (RuntimeException e) { throw new IllegalArgumentException("Invalid task JSONL line " + lineNo, e); }
        }
        if (tasks.isEmpty()) throw new IllegalArgumentException("Empty task dataset");
        return new AgentBenchmarkDataset(tasks, tasks.getFirst().knowledgeBaseId());
    }

    public void requireBenchmarkCoverage() {
        Map<AgentMode, Integer> minimum = Map.of(AgentMode.RETRIEVAL_FIRST, 30, AgentMode.REACT, 35,
                AgentMode.PLAN_EXECUTE_REFLECT, 35);
        Set<String> present = new HashSet<>();
        tasks.forEach(t -> present.addAll(t.tags()));
        if (tasks.size() < 100 || !present.containsAll(TAGS)
                || minimum.entrySet().stream().anyMatch(e -> tasks.stream().filter(t -> t.mode() == e.getKey()).count() < e.getValue())) {
            throw new IllegalArgumentException("Require >=100 tasks, mode counts >=30/35/35 and all scenario tags");
        }
    }

    public record Fault(int turn, String toolName, int occurrence, ToolAction action) {
        public Fault {
            if (turn < 1 || turn > 4 || !"searchKnowledgeBase".equals(toolName) || occurrence < 1 || occurrence > 12
                    || action == null || action == ToolAction.RUN) throw new IllegalArgumentException("Invalid scoped fault");
        }
    }

    public record Task(String caseId, long knowledgeBaseId, AgentMode mode, List<String> turns, int maxTurns,
                       List<String> tags, Boolean answerable, String referenceAnswer, List<RequiredFact> requiredFacts,
                       Double factCoverageThreshold, List<Long> acceptableChunkIds, List<String> requiredToolCalls,
                       Map<String, Integer> minimumSuccessfulToolCalls, List<String> forbiddenBehaviors,
                       Boolean mustHaveValidCitation, AgentResult.Status expectedStatus, int maxToolCalls,
                       int maxModelCalls, Integer maxReplansPerTurn, int minFinalReplans, Fault fault, String labelProvenance) {
        public Task {
            turns = turns == null ? null : List.copyOf(turns);
            tags = tags == null ? null : List.copyOf(tags);
            requiredFacts = requiredFacts == null ? null : List.copyOf(requiredFacts);
            acceptableChunkIds = acceptableChunkIds == null ? null : List.copyOf(acceptableChunkIds);
            requiredToolCalls = requiredToolCalls == null ? null : List.copyOf(requiredToolCalls);
            minimumSuccessfulToolCalls = minimumSuccessfulToolCalls == null ? null : Map.copyOf(minimumSuccessfulToolCalls);
            forbiddenBehaviors = forbiddenBehaviors == null ? null : List.copyOf(forbiddenBehaviors);
            if (caseId == null || !caseId.matches("[A-Za-z][A-Za-z0-9_-]{0,63}") || mode == null
                    || turns == null || turns.isEmpty() || maxTurns < 1 || maxTurns > 4 || turns.size() > maxTurns
                    || turns.stream().anyMatch(q -> q.isBlank() || q.length() > 2_000)
                    || tags == null || tags.isEmpty() || !TAGS.containsAll(tags) || new HashSet<>(tags).size() != tags.size()
                    || requiredToolCalls == null || !TOOLS.containsAll(requiredToolCalls)
                    || new HashSet<>(requiredToolCalls).size() != requiredToolCalls.size()
                    || minimumSuccessfulToolCalls == null || !minimumSuccessfulToolCalls.keySet().equals(new HashSet<>(requiredToolCalls))
                    || minimumSuccessfulToolCalls.values().stream().anyMatch(n -> n == null || n < 1 || n > 12)
                    || forbiddenBehaviors == null || !new HashSet<>(forbiddenBehaviors).equals(FORBIDDEN)
                    || mustHaveValidCitation == null || !mustHaveValidCitation.equals(answerable)
                    || expectedStatus != AgentResult.Status.COMPLETED || maxToolCalls < 0 || maxToolCalls > 48
                    || minimumSuccessfulToolCalls.values().stream().mapToInt(Integer::intValue).sum() > maxToolCalls
                    || maxModelCalls < 1 || maxModelCalls > 48 || maxReplansPerTurn == null
                    || maxReplansPerTurn < 0 || maxReplansPerTurn > 1 || minFinalReplans < 0
                    || minFinalReplans > maxReplansPerTurn
                    || (mode != AgentMode.PLAN_EXECUTE_REFLECT && maxReplansPerTurn != 0)
                    || (mode == AgentMode.RETRIEVAL_FIRST && (!requiredToolCalls.isEmpty() || maxToolCalls != 0 || fault != null))
                    || (fault != null && (fault.turn() > turns.size() || (fault.action() == ToolAction.EMPTY_EVIDENCE
                        && (mode != AgentMode.PLAN_EXECUTE_REFLECT || minFinalReplans != 1 || fault.turn() != turns.size()))))
                    || (tags.contains("MULTI_TURN") != (turns.size() > 1))
                    || (tags.contains("COREFERENCE") && turns.size() < 2)
                    || ((tags.contains("MULTIPLE_TOOLS") || tags.contains("MULTI_STEP"))
                        && minimumSuccessfulToolCalls.values().stream().mapToInt(Integer::intValue).sum() < 2)
                    || (tags.contains("REFUSAL") != Boolean.FALSE.equals(answerable))
                    || (tags.contains("REPLAN") != (fault != null && fault.action() == ToolAction.EMPTY_EVIDENCE))
                    || (tags.contains("TOOL_FAILURE") != (fault != null && fault.action() == ToolAction.FAIL))) {
                throw new IllegalArgumentException("Incomplete or inconsistent task criteria: " + caseId);
            }
            // Reuse M19B's fact/evidence label validation and its semantic review contract.
            new RagAccuracyDataset.Sample(caseId, knowledgeBaseId, turns.getLast(), answerable
                    ? RagAccuracyDataset.QuestionType.SINGLE_DOCUMENT : RagAccuracyDataset.QuestionType.UNANSWERABLE,
                    answerable, referenceAnswer, requiredFacts, factCoverageThreshold, acceptableChunkIds, List.of(), labelProvenance);
            if (tags.contains("CROSS_DOCUMENT") && (acceptableChunkIds.size() < 2 || requiredFacts.size() < 2)) {
                throw new IllegalArgumentException("Comparison needs multiple labeled chunks");
            }
        }

        public RagAccuracyDataset.Sample answerSample() {
            return new RagAccuracyDataset.Sample(caseId, knowledgeBaseId, turns.getLast(), answerable
                    ? RagAccuracyDataset.QuestionType.SINGLE_DOCUMENT : RagAccuracyDataset.QuestionType.UNANSWERABLE,
                    answerable, referenceAnswer, requiredFacts, factCoverageThreshold, acceptableChunkIds, List.of(), labelProvenance);
        }
    }
}
