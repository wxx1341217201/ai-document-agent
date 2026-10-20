package com.wxx.aidocumentagent.recovery.benchmark;

import org.hibernate.type.format.jackson.Jackson3JsonFormatMapper;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.assertThat;

/** Regression for real MySQL reads choosing Jackson 2 for Jackson 3 JsonNode entity fields. */
class RecoveryPersistenceJsonTest {
    @Test void officialHibernateJackson3MapperPreservesStructuredCheckpointAndNulls() {
        JsonMapper mapper = new JsonMapper();
        var format = new Jackson3JsonFormatMapper(mapper);
        JsonNode checkpoint = mapper.readTree("{\"answer\":\"最终答案\",\"steps\":[{\"stepId\":\"fixture_read\",\"status\":\"SUCCEEDED\"}],\"failure\":null}");
        String json = format.toString(checkpoint, JsonNode.class);
        JsonNode restored = format.fromString(json, JsonNode.class);
        assertThat(restored).isEqualTo(checkpoint);
        assertThat(restored.path("answer").asText()).isEqualTo("最终答案");
    }
    @Test void officialMapperRoundTripsEmptyCitationsAndToolArgumentsWithoutPojoMetadata() {
        var mapper = new JsonMapper();
        var format = new Jackson3JsonFormatMapper(mapper);
        for (String payload : java.util.List.of("[]", "{}", "{\"topK\":\"20\"}", "null")) {
            JsonNode original = mapper.readTree(payload);
            String stored = format.toString(original, JsonNode.class);
            assertThat((JsonNode) format.fromString(stored, JsonNode.class)).isEqualTo(original);
            assertThat(stored).doesNotContain("valueNode", "nodeType", "containerNode");
        }
    }
}
