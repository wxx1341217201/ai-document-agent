package com.wxx.aidocumentagent.common.web;

import java.util.Map;

import com.wxx.aidocumentagent.common.api.ApiResponse;
import com.wxx.aidocumentagent.common.api.BusinessException;
import com.wxx.aidocumentagent.common.api.CommonErrorCode;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ApiSupportWebMvcTest {

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new TestController())
                .setControllerAdvice(new GlobalExceptionHandler())
                .addFilter(new TraceIdFilter())
                .build();
    }

    @Test
    void 成功响应使用统一包络并透传追踪标识() throws Exception {
        mockMvc.perform(get("/test/success").header(TraceIdFilter.TRACE_ID_HEADER, "client-trace-001"))
                .andExpect(status().isOk())
                .andExpect(header().string(TraceIdFilter.TRACE_ID_HEADER, "client-trace-001"))
                .andExpect(jsonPath("$.code").value(ApiResponse.SUCCESS_CODE))
                .andExpect(jsonPath("$.message").value(ApiResponse.SUCCESS_MESSAGE))
                .andExpect(jsonPath("$.data.name").value("文档"))
                .andExpect(jsonPath("$.traceId").value("client-trace-001"));
    }

    @Test
    void 业务异常使用声明的冲突错误码() throws Exception {
        mockMvc.perform(get("/test/conflict"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONFLICT"))
                .andExpect(jsonPath("$.message").value("文档正在处理"))
                .andExpect(jsonPath("$.data").doesNotExist())
                .andExpect(jsonPath("$.traceId").isNotEmpty());
    }

    @Test
    void 资源不存在使用统一未找到响应() throws Exception {
        mockMvc.perform(get("/test/not-found"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"))
                .andExpect(jsonPath("$.message").value("资源不存在"))
                .andExpect(jsonPath("$.traceId").isNotEmpty());
    }

    @Test
    void 校验失败返回字段信息但不返回原始值() throws Exception {
        mockMvc.perform(post("/test/validate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.data[0].field").value("title"))
                .andExpect(jsonPath("$.data[0].message").value("标题不能为空"))
                .andExpect(jsonPath("$.data[0].rejectedValue").doesNotExist())
                .andExpect(jsonPath("$.traceId").isNotEmpty());
    }

    @Test
    void 请求参数校验失败返回参数名称() throws Exception {
        mockMvc.perform(get("/test/query").param("name", ""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.data[0].field").value("name"))
                .andExpect(jsonPath("$.data[0].message").value("名称不能为空"));
    }

    @Test
    void 未捕获异常返回安全的内部错误() throws Exception {
        mockMvc.perform(get("/test/unexpected"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
                .andExpect(jsonPath("$.message").value("系统繁忙，请稍后重试"))
                .andExpect(jsonPath("$.message").value(not("SQLSTATE[42000] / D:\\secrets\\app.yml")))
                .andExpect(jsonPath("$.traceId").isNotEmpty());
    }

    @RestController
    @RequestMapping("/test")
    static class TestController {

        @GetMapping("/success")
        ApiResponse<Map<String, String>> success() {
            return ApiResponse.success(Map.of("name", "文档"), TraceIdFilter.currentTraceId());
        }

        @GetMapping("/conflict")
        void conflict() {
            throw new BusinessException(CommonErrorCode.CONFLICT, "文档正在处理");
        }

        @GetMapping("/not-found")
        void notFound() {
            throw new BusinessException(CommonErrorCode.RESOURCE_NOT_FOUND);
        }

        @PostMapping("/validate")
        ApiResponse<ValidationRequest> validate(@Valid @RequestBody ValidationRequest request) {
            return ApiResponse.success(request, TraceIdFilter.currentTraceId());
        }

        @GetMapping("/query")
        ApiResponse<Map<String, String>> query(@RequestParam @NotBlank(message = "名称不能为空") String name) {
            return ApiResponse.success(Map.of("name", name), TraceIdFilter.currentTraceId());
        }

        @GetMapping("/unexpected")
        void unexpected() {
            throw new IllegalStateException("SQLSTATE[42000] / D:\\secrets\\app.yml");
        }
    }

    record ValidationRequest(
            @NotBlank(message = "标题不能为空")
            @Size(max = 128, message = "标题不能超过128个字符")
            String title) {
    }
}
