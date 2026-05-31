package org.example.plan.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.plan.domain.Intent;
import org.example.plan.llm.LLMGateway;
import org.example.plan.config.PlannerConfig;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;

/**
 * 意图分类器
 * 负责理解用户查询，提取实体和意图类型
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class IntentClassifier {

    private final LLMGateway llmGateway;
    private final PlannerConfig plannerConfig;

    /**
     * 理解用户意图
     */
    public Intent understand(String userQuery) {
        log.info("Understanding intent for query: {}", userQuery);

        Intent.IntentType intentType = classifyIntentType(userQuery);
        Map<String, Object> entities = extractEntities(userQuery);

        return Intent.builder()
                .intentId(java.util.UUID.randomUUID().toString())
                .rawQuery(userQuery)
                .intentType(intentType)
                .entities(entities)
                .confidence(plannerConfig.getConfidenceThreshold())
                .requiredMetrics(extractMetrics(userQuery))
                .build();
    }

    /**
     * 分类意图类型
     */
    private Intent.IntentType classifyIntentType(String query) {
        String lowerQuery = query.toLowerCase();

        if (lowerQuery.contains("对比") || lowerQuery.contains("比较")) {
            return Intent.IntentType.COMPARISON;
        } else if (lowerQuery.contains("趋势") || lowerQuery.contains("增长") || lowerQuery.contains("分析")) {
            return Intent.IntentType.TREND_ANALYSIS;
        } else if (lowerQuery.contains("报告")) {
            return Intent.IntentType.REPORT_GENERATE;
        } else {
            return Intent.IntentType.METRIC_QUERY;
        }
    }

    /**
     * 提取实体
     */
    private Map<String, Object> extractEntities(String query) {
        Map<String, Object> entities = new HashMap<>();

        // 提取地区
        if (query.contains("华东")) {
            entities.put("region", "华东");
        } else if (query.contains("华南")) {
            entities.put("region", "华南");
        } else if (query.contains("华北")) {
            entities.put("region", "华北");
        }

        // 提取时间范围
        if (query.contains("7天")) {
            entities.put("timeRange", "last_7_days");
        } else if (query.contains("30天")) {
            entities.put("timeRange", "last_30_days");
        } else if (query.contains("本月")) {
            entities.put("timeRange", "current_month");
        } else {
            entities.put("timeRange", "last_7_days"); // 默认7天
        }

        return entities;
    }

    /**
     * 提取指标
     */
    private java.util.List<String> extractMetrics(String query) {
        java.util.List<String> metrics = new java.util.ArrayList<>();

        if (query.contains("销售")) {
            metrics.add("sales");
        }
        if (query.contains("用户")) {
            metrics.add("users");
        }
        if (query.contains("订单")) {
            metrics.add("orders");
        }

        if (metrics.isEmpty()) {
            metrics.add("sales"); // 默认销售指标
        }

        return metrics;
    }

    /**
     * 检测是否需要澄清用户意图
     */
    public boolean needsClarification(Intent intent) {
        return intent.getConfidence() < plannerConfig.getConfidenceThreshold()
                || intent.getRequiredMetrics().isEmpty();
    }
}