package com.wxx.aidocumentagent.system.api;

import com.wxx.aidocumentagent.common.api.ApiResponse;
import com.wxx.aidocumentagent.common.web.TraceIdFilter;
import com.wxx.aidocumentagent.system.application.SystemHealthService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class SystemController {
    private final SystemHealthService health;
    public SystemController(SystemHealthService health) { this.health = health; }

    @GetMapping("/api/v1/system/health")
    public ApiResponse<SystemHealthResponse> health() {
        return ApiResponse.success(health.health(), TraceIdFilter.currentTraceId());
    }
}
