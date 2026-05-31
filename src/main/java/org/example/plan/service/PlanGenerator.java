package org.example.plan.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.plan.domain.ExecutionStep;
import org.example.plan.domain.Intent;
import org.example.plan.domain.Plan;
import org.example.tool.ToolRegistry;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * 计划生成器
 * 基于意图生成可执行的步骤计划
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PlanGenerator {

    private final ToolRegistry toolRegistry;

    /**
     * 生成执行计划
     */
    public Plan generate(Intent intent) {
        log.info("Generating plan for intent: {}", intent.getIntentId());

        Plan plan = Plan.create(intent.getIntentId());

        // 根据意图类型生成不同的计划
        switch (intent.getIntentType()) {
            case METRIC_QUERY -> generateMetricQueryPlan(intent, plan);
            case COMPARISON -> generateComparisonPlan(intent, plan);
            case TREND_ANALYSIS -> generateTrendAnalysisPlan(intent, plan);
            default -> generateDefaultPlan(intent, plan);
        }

        log.info("Generated plan with {} steps", plan.getStepCount());
        return plan;
    }

    private void generateMetricQueryPlan(Intent intent, Plan plan) {
        // Step 1: 识别取数逻辑
        plan.addStep(ExecutionStep.create(
                1,
                "recognize_logic",
                "logic_recognition",
                Map.of(
                        "query", intent.getRawQuery(),
                        "entities", intent.getEntities()
                )
        ));

        // Step 2: 获取指标值
        plan.addStep(ExecutionStep.create(
                2,
                "fetch_metric",
                "metric_value_api",
                Map.of(
                        "metric", intent.getRequiredMetrics().get(0),
                        "filters", intent.getEntities()
                )
        ));

        // Step 3: 格式化结果
        plan.addStep(ExecutionStep.create(
                3,
                "format_result",
                "formatter",
                Map.of("format", "table")
        ));
    }

    private void generateComparisonPlan(Intent intent, Plan plan) {
        // Step 1: 识别取数逻辑
        plan.addStep(ExecutionStep.create(
                1,
                "recognize_logic",
                "logic_recognition",
                Map.of("query", intent.getRawQuery())
        ));

        // Step 2: 获取主指标
        plan.addStep(ExecutionStep.create(
                2,
                "fetch_primary_metric",
                "metric_value_api",
                Map.of("metric", intent.getRequiredMetrics().get(0))
        ));

        // Step 3: 对比分析
        plan.addStep(ExecutionStep.create(
                3,
                "compare",
                "comparison_tool",
                Map.of("operation", "compare")
        ));

        // Step 4: 格式化
        plan.addStep(ExecutionStep.create(
                4,
                "format_result",
                "formatter",
                Map.of("format", "table")
        ));
    }

    private void generateTrendAnalysisPlan(Intent intent, Plan plan) {
        // Step 1: 识别取数逻辑
        plan.addStep(ExecutionStep.create(
                1,
                "recognize_logic",
                "logic_recognition",
                Map.of("query", intent.getRawQuery())
        ));

        // Step 2: 获取指标值
        plan.addStep(ExecutionStep.create(
                2,
                "fetch_metric",
                "metric_value_api",
                Map.of("metric", intent.getRequiredMetrics().get(0))
        ));

        // Step 3: 趋势分析
        plan.addStep(ExecutionStep.create(
                3,
                "analyze_trend",
                "trend_tool",
                Map.of("period", "day")
        ));

        // Step 4: 格式化
        plan.addStep(ExecutionStep.create(
                4,
                "format_result",
                "formatter",
                Map.of("format", "chart")
        ));
    }

    private void generateDefaultPlan(Intent intent, Plan plan) {
        generateMetricQueryPlan(intent, plan);
    }

    /**
     * 验证计划可行性
     */
    public boolean validate(Plan plan) {
        if (plan.getSteps().isEmpty()) {
            return false;
        }

        for (ExecutionStep step : plan.getSteps()) {
            if (!toolRegistry.hasTool(step.getTool())) {
                log.warn("Plan references unknown tool: {}", step.getTool());
                return false;
            }
        }

        return true;
    }
}