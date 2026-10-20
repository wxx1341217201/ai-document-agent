package com.wxx.aidocumentagent.agent.execution;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import com.wxx.aidocumentagent.agent.AgentCheckpointSink;
import com.wxx.aidocumentagent.agent.AgentConversationContext;
import com.wxx.aidocumentagent.agent.AgentErrorCode;
import com.wxx.aidocumentagent.agent.AgentExecutionContext;
import com.wxx.aidocumentagent.agent.AgentMode;
import com.wxx.aidocumentagent.agent.AgentRequest;
import com.wxx.aidocumentagent.agent.react.AgentTool;
import com.wxx.aidocumentagent.agent.react.ToolExecutionContext;
import com.wxx.aidocumentagent.agent.react.ToolRegistry;
import jakarta.validation.Validation;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;

class DurableToolSafetyTest {
    @Test
    void unmarkedToolCannotCauseSideEffectDuringRecoverableRun() {
        AtomicInteger writes = new AtomicInteger();
        AgentTool<Input, String> tool = new AgentTool<>() {
            @Override public String name() { return "writeData"; }
            @Override public String description() { return "test"; }
            @Override public Class<Input> inputType() { return Input.class; }
            @Override public String execute(Input input, ToolExecutionContext context) {
                writes.incrementAndGet();
                return "done";
            }
        };
        try (var validatorFactory = Validation.buildDefaultValidatorFactory()) {
            ToolRegistry registry = new ToolRegistry(List.of(tool), new JsonMapper(), validatorFactory.getValidator());
            AgentRequest request = new AgentRequest("question", 7L, null, AgentMode.REACT, Map.of());
            AgentExecutionContext context = AgentExecutionContext.forDurableRequest(request, "trace",
                    Instant.now().plusSeconds(10), new AgentConversationContext(7L, null, List.of()),
                    event -> { }, () -> false, "execution-id", AgentCheckpointSink.NO_OP);
            var result = registry.execute("writeData", "{\"value\":1}",
                    new ToolExecutionContext(7L, context, context.deadline(), 5));
            assertThat(result.errorCode()).isEqualTo(AgentErrorCode.TOOL_REQUIRES_IDEMPOTENCY);
            assertThat(writes).hasValue(0);
        }
    }

    record Input(int value) { }
}
