package com.wxx.aidocumentagent.system.api;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** 仅公开固定组件名和状态，不透传连接信息或异常详情。 */
public record SystemHealthResponse(Status status, Map<String, Status> components) {
    public SystemHealthResponse {
        components = Collections.unmodifiableMap(new LinkedHashMap<>(components));
    }
    public enum Status { UP, DOWN, UNKNOWN }
}
