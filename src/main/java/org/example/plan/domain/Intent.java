package org.example.plan.domain;

import lombok.Builder;
import lombok.Data;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 意图 - 用户意图
 */
@Data
@Builder
public class Intent {
    private String intentId;                    // 意图ID
    private String rawQuery;                     // 用户原始查询
    private String understoodQuery;             // LLM理解后的查询
    private IntentType intentType;               // 意图类型
    private Map<String, Object> entities;         // 提取的实体
    private List<String> requiredMetrics;        // 需要查询的指标
    private Double confidence;                  // 置信度

    public enum IntentType {
        METRIC_QUERY,      // 指标查询
        COMPARISON,        // 对比分析
        TREND_ANALYSIS,    // 趋势分析
        REPORT_GENERATE,   // 报告生成
        CLARIFICATION      // 需求澄清
    }

    public static Intent create(String rawQuery) {
        return Intent.builder()
                .intentId(UUID.randomUUID().toString())
                .rawQuery(rawQuery)
                .confidence(0.7)
                .build();
    }
}