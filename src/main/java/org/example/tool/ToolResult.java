package org.example.tool;

import lombok.Builder;
import lombok.Data;

/**
 * 工具执行结果
 */
@Data
@Builder
public class ToolResult {
    private boolean success;
    private Object data;
    private String error;
    private long durationMs;
    private Map<String, Object> metadata;

    public static ToolResult success(Object data) {
        return ToolResult.builder()
            .success(true)
            .data(data)
            .durationMs(System.currentTimeMillis())
            .build();
    }

    public static ToolResult success(Object data, long durationMs) {
        return ToolResult.builder()
            .success(true)
            .data(data)
            .durationMs(durationMs)
            .build();
    }

    public static ToolResult failure(String error) {
        return ToolResult.builder()
            .success(false)
            .error(error)
            .durationMs(System.currentTimeMillis())
            .build();
    }
}