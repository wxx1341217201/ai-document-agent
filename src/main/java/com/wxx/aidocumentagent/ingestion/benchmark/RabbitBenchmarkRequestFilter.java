package com.wxx.aidocumentagent.ingestion.benchmark;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.filter.OncePerRequestFilter;

/** Captures server arrival before multipart parsing and response completion after serialization. */
public class RabbitBenchmarkRequestFilter extends OncePerRequestFilter {
    private final byte[] token;
    private final RabbitBenchmarkMetrics metrics;
    public RabbitBenchmarkRequestFilter(String token, RabbitBenchmarkMetrics metrics) {
        this.token = token.getBytes(StandardCharsets.UTF_8);
        this.metrics = metrics;
    }
    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String supplied = request.getHeader("X-M19E-Token");
        if (supplied == null || !MessageDigest.isEqual(token, supplied.getBytes(StandardCharsets.UTF_8))) {
            response.sendError(403);
            return;
        }
        request.setAttribute("m19e.arrived", RabbitBenchmarkMetrics.now());
        boolean completed = false;
        try { chain.doFilter(request, response); completed = true; }
        finally {
            // An unhandled servlet exception sets HTTP 500 outside this filter, after finally.
            if (completed && request.getAttribute("m19e.documentId") instanceof Long id && response.getStatus() < 400)
                metrics.acknowledged(id, RabbitBenchmarkMetrics.now());
        }
    }
}
