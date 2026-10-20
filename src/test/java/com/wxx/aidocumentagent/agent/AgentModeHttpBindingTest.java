package com.wxx.aidocumentagent.agent;

import com.wxx.aidocumentagent.common.web.GlobalExceptionHandler;
import com.wxx.aidocumentagent.common.web.TraceIdFilter;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 验证将模式字段接入主协议时，Jackson 和现有异常处理器会拒绝非法枚举值。 */
class AgentModeHttpBindingTest {

    private final MockMvc mockMvc = MockMvcBuilders.standaloneSetup(new ModeBindingProbe())
            .setControllerAdvice(new GlobalExceptionHandler())
            .addFilter(new TraceIdFilter())
            .build();

    @Test
    void 非白名单模式在HTTP绑定阶段返回400() throws Exception {
        mockMvc.perform(post("/agent-mode-binding-probe")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"mode\":\"com.example.CustomAgent\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("BAD_REQUEST"));
    }

    @RestController
    static class ModeBindingProbe {

        @PostMapping("/agent-mode-binding-probe")
        AgentMode bind(@RequestBody ModeBody body) {
            return body.mode();
        }
    }

    record ModeBody(AgentMode mode) {
    }
}
