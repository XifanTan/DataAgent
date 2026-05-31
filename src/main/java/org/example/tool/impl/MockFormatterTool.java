package org.example.tool.impl;

import lombok.extern.slf4j.Slf4j;
import org.example.tool.Tool;
import org.example.tool.ToolContext;
import org.example.tool.ToolResult;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * Mock工具 - 格式化工具
 * 将数据格式化为指定格式
 */
@Slf4j
@Component
public class MockFormatterTool implements Tool {

    @Override
    public String getName() {
        return "formatter";
    }

    @Override
    public String getDescription() {
        return "将数据格式化为指定格式（table/chart/json）";
    }

    @Override
    public String getCategory() {
        return "format";
    }

    @Override
    public Map<String, Object> getParametersSchema() {
        return Map.of(
            "data", Map.of(
                "type", "object",
                "required", true,
                "description", "要格式化的数据"
            ),
            "format", Map.of(
                "type", "string",
                "required", true,
                "description", "目标格式: table/chart/json"
            )
        );
    }

    @Override
    public ToolResult execute(Map<String, Object> params, ToolContext context) {
        long startTime = System.currentTimeMillis();
        log.info("MockFormatterTool executing with params: {}", params);

        try {
            Object data = params.get("data");
            String format = params.get("format").toString();

            // Mock格式化结果
            Object formattedResult = switch (format) {
                case "table" -> formatAsTable(data);
                case "chart" -> formatAsChart(data);
                case "json" -> data.toString();
                default -> formatAsTable(data);
            };

            long duration = System.currentTimeMillis() - startTime;
            return ToolResult.builder()
                    .success(true)
                    .data(formattedResult)
                    .durationMs(duration)
                    .metadata(Map.of("format", format))
                    .build();

        } catch (Exception e) {
            log.error("MockFormatterTool failed", e);
            return ToolResult.failure("Formatting failed: " + e.getMessage());
        }
    }

    private Map<String, Object> formatAsTable(Object data) {
        return Map.of(
            "type", "table",
            "columns", List.of("日期", "指标值", "单位"),
            "rows", List.of(
                List.of("2026-05-31", "123,456", "CNY"),
                List.of("2026-05-30", "115,000", "CNY"),
                List.of("2026-05-29", "108,500", "CNY")
            ),
            "summary", Map.of(
                "total", 346956,
                "avg", 115652
            )
        );
    }

    private Map<String, Object> formatAsChart(Object data) {
        return Map.of(
            "type", "chart",
            "chartType", "line",
            "labels", List.of("05-25", "05-26", "05-27", "05-28", "05-29", "05-30", "05-31"),
            "datasets", List.of(
                Map.of(
                    "label", "销售额",
                    "data", List.of(80000, 90000, 85000, 95000, 100000, 110000, 105000)
                )
            )
        );
    }
}