package com.wxx.aidocumentagent.common.web;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;

class TraceIdFilterTest {

    private final TraceIdFilter filter = new TraceIdFilter();

    @Test
    void 合法追踪标识会复用并在请求结束后清理MDC() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(TraceIdFilter.TRACE_ID_HEADER, "client-trace_001");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, assertCurrentTraceId("client-trace_001"));

        assertThat(response.getHeader(TraceIdFilter.TRACE_ID_HEADER)).isEqualTo("client-trace_001");
        assertThat(MDC.get(TraceIdFilter.MDC_TRACE_ID_KEY)).isNull();
    }

    @Test
    void 非法追踪标识会替换为新的安全值() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(TraceIdFilter.TRACE_ID_HEADER, "not a legal trace id");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, (ignoredRequest, ignoredResponse) -> {
        });

        assertThat(response.getHeader(TraceIdFilter.TRACE_ID_HEADER))
                .matches("^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$")
                .isNotEqualTo("not a legal trace id");
        assertThat(MDC.get(TraceIdFilter.MDC_TRACE_ID_KEY)).isNull();
    }

    @Test
    void 并发请求之间追踪标识隔离且各线程MDC会清理() throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(4);
        try {
            List<Callable<String>> tasks = new ArrayList<>();
            for (int index = 0; index < 20; index++) {
                String expectedTraceId = "parallel-" + index;
                tasks.add(() -> executeRequest(expectedTraceId));
            }

            List<Future<String>> futures = executor.invokeAll(tasks);
            for (int index = 0; index < futures.size(); index++) {
                assertThat(futures.get(index).get()).isEqualTo("parallel-" + index + "|null");
            }
        }
        finally {
            executor.shutdownNow();
        }
    }

    private String executeRequest(String expectedTraceId) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(TraceIdFilter.TRACE_ID_HEADER, expectedTraceId);
        MockHttpServletResponse response = new MockHttpServletResponse();
        StringBuilder observed = new StringBuilder();

        filter.doFilter(request, response, (ignoredRequest, ignoredResponse) ->
                observed.append(MDC.get(TraceIdFilter.MDC_TRACE_ID_KEY)));

        return observed + "|" + MDC.get(TraceIdFilter.MDC_TRACE_ID_KEY);
    }

    private FilterChain assertCurrentTraceId(String expectedTraceId) {
        return (ignoredRequest, ignoredResponse) ->
                assertThat(MDC.get(TraceIdFilter.MDC_TRACE_ID_KEY)).isEqualTo(expectedTraceId);
    }
}
