package org.example.tool;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 工具执行上下文
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ToolContext {
    private String sessionId;
    private String toolName;
    private Long startTime;

    public static ToolContext of(String sessionId, String toolName) {
        return new ToolContext(sessionId, toolName, System.currentTimeMillis());
    }
}