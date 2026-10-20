package com.wxx.aidocumentagent.agent;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.assertThat;

class ClaimEvidenceValidatorTest {
    private final ClaimEvidenceValidator validator = new ClaimEvidenceValidator();
    private final AgentCitation citation = new AgentCitation(7, "C1", 21, "policy.txt", 11, null, null, "The service retries 5 times.");
    private final List<ClaimEvidenceValidator.Visible> visible = List.of(new ClaimEvidenceValidator.Visible(citation,
            "The service retries 5 times.\nIt waits 20 seconds."));

    @Test void retrievalLanguageHintUsesActualSourceLanguageWithoutInjectingAnswersOrAliases() {
        var hint = EvidenceAnswerSupport.retrievalLanguageHint(List.of("The service was started and the worker said that it was ready for the next task. ".repeat(30)));
        assertThat(hint).contains("MUST be a concise English search query").doesNotContain("Alice", "Darcy", "20 seconds");
        assertThat(EvidenceAnswerSupport.retrievalLanguageHint(List.of("这里是中文资料，系统检索当前知识库中的实际原文。".repeat(30)))).isBlank();
        assertThat(EvidenceAnswerSupport.retrievalLanguageHint(List.of("Le service attend une réponse pour chaque requête. ".repeat(30)))).isBlank();
    }

    @Test void aSingleJsonFenceAndRedundantBoundMarkersAreNormalizedButUnknownMarkersStillFail() {
        var wrapped = "```json\n" + draft("It retries 5 times. [C1]", "C1", "The service retries 5 times.") + "\n```";
        var result = validator.validate(wrapped, 7, visible);
        assertThat(result.complete()).isTrue();
        assertThat(result.answer()).isEqualTo("It retries 5 times. [C1]");
        assertThat(validator.validate(wrapped + " extra outside JSON", 7, visible).valid()).isFalse();
        assertThat(validator.validate(draft("It retries 5 times. [C99]", "C1", "The service retries 5 times."), 7, visible).error())
                .isEqualTo(ClaimEvidenceValidator.Error.UNKNOWN_CITATION);
    }

    private String draft(String text, String id, String quote) {
        return new JsonMapper().writeValueAsString(Map.of("status", "ANSWERED", "missing", List.of(), "claims", List.of(
                Map.of("text", text, "supports", List.of(Map.of("citationId", id, "quote", quote))))));
    }

    @Test void verificationContextIsBoundedAndComesOnlyFromTheSameVisibleSnapshot() {
        var checked = validator.validate(draft("It waits 20 seconds.", "C1", "It waits 20 seconds."), 7, visible);
        var input = ClaimSupportVerifier.draftData(checked, visible);
        assertThat(input).contains("contextBefore", "The service retries 5 times.");
        var proof = ClaimSupportVerifier.fingerprints(checked, visible).getFirst();
        assertThat(proof.get("sha256")).isEqualTo(ClaimEvidenceValidator.sha256(visible.getFirst().text()));
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> ClaimSupportVerifier.draftData(checked,
                List.of(new ClaimEvidenceValidator.Visible(citation, "a changed source"))))
                .isInstanceOf(com.wxx.aidocumentagent.common.api.BusinessException.class);
    }

    @Test void derivesOffsetsAndCitationMetadataFromTheActualVisibleSnapshot() {
        var result = validator.validate(draft("It waits 20 seconds.", "C1", "It waits 20 seconds."), 7, visible);
        assertThat(result.complete()).isTrue();
        assertThat(result.answer()).isEqualTo("It waits 20 seconds. [C1]");
        var span = result.bindings().getFirst().sources().getFirst();
        assertThat(span.start()).isEqualTo(29);
        assertThat(visible.getFirst().text().substring(span.start(), span.end())).isEqualTo(span.quote());
        assertThat(span.visibleSha256()).isEqualTo(ClaimEvidenceValidator.sha256(visible.getFirst().text()));
        assertThat(result.citations()).containsExactly(citation);
    }
    @Test void rejectsForeignUnknownForgedInvisibleAndContradictoryNumericEvidence() {
        assertThat(validator.validate(draft("Fact", "C1", "The service retries 5 times."), 99, visible).error())
                .isEqualTo(ClaimEvidenceValidator.Error.SCOPE_MISMATCH);
        assertThat(validator.validate(draft("Fact", "C9", "The service retries 5 times."), 7, visible).error())
                .isEqualTo(ClaimEvidenceValidator.Error.UNKNOWN_CITATION);
        assertThat(validator.validate(draft("Fact", "C1", "The service never retries."), 7, visible).error())
                .isEqualTo(ClaimEvidenceValidator.Error.QUOTE_NOT_VISIBLE);
        assertThat(validator.validate(draft("It retries 9 times.", "C1", "The service retries 5 times."), 7, visible).error())
                .isEqualTo(ClaimEvidenceValidator.Error.NUMERIC_MISMATCH);
    }
    @Test void locatesFlattenedPrintLineWrapsButKeepsTheRealSourceSpan() {
        var result = validator.validate(draft("The retry policy.", "C1", "times. It waits"), 7, visible);
        assertThat(result.valid()).isTrue();
        assertThat(result.bindings().getFirst().sources().getFirst().quote()).isEqualTo("times.\nIt waits");
    }
    @Test void partialAndRefusalAreDifferentAndUnboundProseCannotSneakIntoAnAnswer() {
        assertThat(validator.validate("{\"status\":\"PARTIAL\",\"claims\":[],\"missing\":[\"other fact\"]}", 7, visible).valid()).isFalse();
        var result = validator.validate("{\"status\":\"INSUFFICIENT\",\"claims\":[],\"missing\":[\"phone number\"]}", 7, visible);
        assertThat(result.complete()).isTrue();
        assertThat(result.citations()).isEmpty();
        assertThat(validator.validate(draft("Invented [C2]", "C1", "The service retries 5 times."), 7, visible).valid()).isFalse();
        assertThat(validator.validate(draft("Fact", "C1", "The service retries 5 times.").replace("\"status\"", "\"answer\":\"extra\",\"status\""), 7, visible).valid()).isFalse();
    }
    @Test void verifierRepairsSchemaOnceWithoutReplayingInvalidContent() {
        var calls = new AtomicInteger();
        var check = new ClaimSupportVerifier().verify("question and sources", (system, user) -> {
            if (calls.incrementAndGet() == 1) return "{\"reasoning\":\"private-invalid-output\"}";
            assertThat(system).contains("INVALID_COVERAGE_SCHEMA").doesNotContain("private-invalid-output");
            return "{\"verdict\":\"SUPPORTED\",\"missing\":[]}";
        });
        assertThat(check.covered()).isTrue();
        assertThat(calls.get()).isEqualTo(2);
    }
    @Test void unknownContradictionAndInvalidSchemasNeverBecomeSupport() {
        for (String verdict : List.of("UNKNOWN", "CONTRADICTED")) {
            var check = new ClaimSupportVerifier().verify("same person different event", (system, user) ->
                    "{\"verdict\":\"" + verdict + "\",\"missing\":[\"wrong event, speaker or unit\"]}");
            assertThat(check.covered()).isFalse();
        }
        var calls = new AtomicInteger();
        var invalid = new ClaimSupportVerifier().verify("data", (system, user) -> { calls.incrementAndGet(); return "{}"; });
        assertThat(invalid.schemaValid()).isFalse();
        assertThat(calls.get()).isEqualTo(2);
    }
}
