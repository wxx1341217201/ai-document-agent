package com.wxx.aidocumentagent.common.web;

import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 将请求追踪标识写入响应头和当前日志 MDC。
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class TraceIdFilter extends OncePerRequestFilter {

    public static final String TRACE_ID_HEADER = "X-Trace-Id";
    public static final String MDC_TRACE_ID_KEY = "traceId";

    private static final Pattern LEGAL_TRACE_ID = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$");

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String traceId = resolveTraceId(request.getHeader(TRACE_ID_HEADER));
        response.setHeader(TRACE_ID_HEADER, traceId);
        MDC.put(MDC_TRACE_ID_KEY, traceId);
        try {
            filterChain.doFilter(request, response);
        }
        finally {
            MDC.remove(MDC_TRACE_ID_KEY);
        }
    }

    public static String currentTraceId() {
        return MDC.get(MDC_TRACE_ID_KEY);
    }

    private String resolveTraceId(String requestTraceId) {
        if (requestTraceId != null && LEGAL_TRACE_ID.matcher(requestTraceId).matches()) {
            return requestTraceId;
        }
        return UUID.randomUUID().toString();
    }
}
