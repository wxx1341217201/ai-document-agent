package com.wxx.aidocumentagent.ingestion.benchmark;

import java.util.concurrent.atomic.AtomicInteger;
import jakarta.servlet.ServletException;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RabbitBenchmarkRequestFilterTest {
    private MockHttpServletRequest request() {
        var request = new MockHttpServletRequest();
        request.addHeader("X-M19E-Token", "benchmark-token");
        request.setAttribute("m19e.documentId", 1L);
        return request;
    }
    private RabbitBenchmarkMetrics metrics() {
        var metrics = new RabbitBenchmarkMetrics();
        metrics.submitted(1, "doc-000", 0, 512, RabbitBenchmarkMetrics.now());
        return metrics;
    }
    @Test void capturesAckOnlyAfterSuccessfulSerialization() throws Exception {
        var metrics = metrics();
        new RabbitBenchmarkRequestFilter("benchmark-token", metrics).doFilter(request(), new MockHttpServletResponse(),
                (req, res) -> res.getWriter().write("serialized"));
        assertThat(metrics.documents().getFirst().ackAt).isNotNull();
    }
    @Test void unhandledFailureMustNotRecordSuccessBeforeContainerSetsHttp500() {
        var metrics = metrics();
        var response = new MockHttpServletResponse();
        assertThatThrownBy(() -> new RabbitBenchmarkRequestFilter("benchmark-token", metrics).doFilter(request(), response,
                (req, res) -> { throw new ServletException("unhandled"); })).isInstanceOf(ServletException.class);
        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(metrics.documents().getFirst().ackAt).isNull();
    }
    @Test void handledHttp500IsNotSuccessfulAcknowledgement() throws Exception {
        var metrics = metrics();
        var response = new MockHttpServletResponse();
        new RabbitBenchmarkRequestFilter("benchmark-token", metrics).doFilter(request(), response,
                (req, res) -> response.setStatus(500));
        assertThat(metrics.documents().getFirst().ackAt).isNull();
    }
    @Test void rejectsUnauthorizedRequestWithoutExecutingSubmission() throws Exception {
        var metrics = metrics();
        var calls = new AtomicInteger();
        var response = new MockHttpServletResponse();
        new RabbitBenchmarkRequestFilter("different-token", metrics).doFilter(request(), response, (req, res) -> calls.incrementAndGet());
        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(calls).hasValue(0);
        assertThat(metrics.documents().getFirst().ackAt).isNull();
    }
}
