package org.example.tool;

import java.util.Map;

/**
 * MCP Tool 接口
 * 所有工具必须实现此接口
 */
public interface Tool {

    /**
     * 获取工具名称
     */
    String getName();

    /**
     * 获取工具描述
     */
    String getDescription();

    /**
     * 获取工具分类
     */
    String getCategory();

    /**
     * 获取参数Schema
     */
    default Map<String, Object> getParametersSchema() {
        return Map.of();
    }

    /**
     * 执行工具
     * @param params 执行参数
     * @param context 工具执行上下文（可为null）
     * @return 工具执行结果
     */
    ToolResult execute(Map<String, Object> params, ToolContext context);
}