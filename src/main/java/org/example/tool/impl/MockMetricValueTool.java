package org.example.tool.impl;

import lombok.extern.slf4j.Slf4j;
import org.example.tool.Tool;
import org.example.tool.ToolContext;
import org.example.tool.ToolResult;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * Mock工具 - 获取指标值
 * 模拟从指标服务API获取数据
 */
@Slf4j
@Component
public class MockMetricValueTool implements Tool {

    private final Random random = new Random();

    @Override
    public String getName() {
        return "metric_value_api";
    }

    @Override
    public String getDescription() {
        return "调用指标服务API获取指标值";
    }

    @Override
    public String getCategory() {
        return "api";
    }

    @Override
    public Map<String, Object> getParametersSchema() {
        return Map.of(
            "metric", Map.of(
                "type", "string",
                "required", true,
                "description", "指标名称"
            ),
            "filters", Map.of(
                "type", "object",
                "required", false,
                "description", "过滤条件"
            ),
            "timeRange", Map.of(
                "type", "object",
                "required", false,
                "description", "时间范围"
            )
        );
    }

    @Override
    public ToolResult execute(Map<String, Object> params, ToolContext context) {
        long startTime = System.currentTimeMillis();
        log.info("MockMetricValueTool executing with params: {}", params);

        try {
            String metric = params.get("metric").toString();
            @SuppressWarnings("unchecked")
            Map<String, Object> filters = (Map<String, Object>) params.getOrDefault("filters", Map.of());
            @SuppressWarnings("unchecked")
            Map<String, Object> timeRange = (Map<String, Object>) params.getOrDefault("timeRange", Map.of());

            // Mock数据 - 模拟指标值
            Object mockData = generateMockMetricData(metric, filters, timeRange);

            long duration = System.currentTimeMillis() - startTime;
            return ToolResult.builder()
                    .success(true)
                    .data(mockData)
                    .durationMs(duration)
                    .metadata(Map.of(
                        "metric", metric,
                        "tool", getName()
                    ))
                    .build();

        } catch (Exception e) {
            log.error("MockMetricValueTool failed", e);
            return ToolResult.failure("Metric query failed: " + e.getMessage());
        }
    }

    private Object generateMockMetricData(String metric, Map<String, Object> filters, Map<String, Object> timeRange) {
        // 生成Mock数据
        int baseValue = switch (metric) {
            case "sales" -> 100000 + random.nextInt(90000);
            case "users" -> 5000 + random.nextInt(5000);
            case "orders" -> 1000 + random.nextInt(900);
            default -> 1000 + random.nextInt(900);
        };

        String region = filters.containsKey("region") ? filters.get("region").toString() : "全国";

        // 构建模拟的时间序列数据
        List<Map<String, Object>> timeSeriesData = List.of(
            Map.of("date", "2026-05-25", "value", baseValue * 0.8),
            Map.of("date", "2026-05-26", "value", baseValue * 0.9),
            Map.of("date", "2026-05-27", "value", baseValue * 0.85),
            Map.of("date", "2026-05-28", "value", baseValue * 0.95),
            Map.of("date", "2026-05-29", "value", baseValue * 1.0),
            Map.of("date", "2026-05-30", "value", baseValue * 1.1),
            Map.of("date", "2026-05-31", "value", baseValue * 1.05)
        );

        return Map.of(
            "metric", metric,
            "region", region,
            "timeRange", timeRange,
            "currentValue", baseValue,
            "unit", "CNY",
            "timeSeriesData", timeSeriesData,
            "totalRecords", 156
        );
    }
}