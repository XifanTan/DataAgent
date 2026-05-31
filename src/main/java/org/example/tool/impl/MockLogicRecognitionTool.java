package org.example.tool.impl;

import lombok.extern.slf4j.Slf4j;
import org.example.tool.Tool;
import org.example.tool.ToolContext;
import org.example.tool.ToolResult;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * Mock工具 - 识别取数逻辑
 * 使用LLM识别用户的取数意图
 */
@Slf4j
@Component
public class MockLogicRecognitionTool implements Tool {

    @Override
    public String getName() {
        return "logic_recognition";
    }

    @Override
    public String getDescription() {
        return "识别用户的取数逻辑，将自然语言转换为结构化的查询参数";
    }

    @Override
    public String getCategory() {
        return "llm";
    }

    @Override
    public Map<String, Object> getParametersSchema() {
        return Map.of(
            "query", Map.of(
                "type", "string",
                "required", true,
                "description", "用户原始查询"
            ),
            "entities", Map.of(
                "type", "object",
                "required", false,
                "description", "已提取的实体信息"
            )
        );
    }

    @Override
    public ToolResult execute(Map<String, Object> params, ToolContext context) {
        long startTime = System.currentTimeMillis();
        log.info("MockLogicRecognitionTool executing with params: {}", params);

        try {
            String query = params.get("query").toString();

            // Mock响应 - 根据query推断取数逻辑
            Map<String, Object> fetchLogic = buildMockFetchLogic(query);

            long duration = System.currentTimeMillis() - startTime;
            return ToolResult.builder()
                    .success(true)
                    .data(fetchLogic)
                    .durationMs(duration)
                    .metadata(Map.of("tool", getName()))
                    .build();

        } catch (Exception e) {
            log.error("MockLogicRecognitionTool failed", e);
            return ToolResult.failure("Logic recognition failed: " + e.getMessage());
        }
    }

    private Map<String, Object> buildMockFetchLogic(String query) {
        // 简单Mock逻辑 - 实际应该调用LLM
        Map<String, Object> logic = Map.of(
            "metric", extractMetric(query),
            "filters", extractFilters(query),
            "timeRange", extractTimeRange(query),
            "aggregation", "sum"
        );
        return logic;
    }

    private String extractMetric(String query) {
        if (query.contains("销售")) return "sales";
        if (query.contains("用户")) return "users";
        if (query.contains("订单")) return "orders";
        return "unknown";
    }

    private Map<String, Object> extractFilters(String query) {
        Map<String, Object> filters = Map.of();
        if (query.contains("华东")) {
            filters = Map.of("region", "华东");
        } else if (query.contains("华南")) {
            filters = Map.of("region", "华南");
        }
        return filters;
    }

    private Map<String, Object> extractTimeRange(String query) {
        if (query.contains("7天")) {
            return Map.of("type", "last_7_days");
        } else if (query.contains("30天")) {
            return Map.of("type", "last_30_days");
        } else if (query.contains("本月")) {
            return Map.of("type", "current_month");
        }
        return Map.of("type", "last_7_days");
    }
}