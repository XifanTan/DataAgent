package org.example.plan.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.memory.DataCache;
import org.example.plan.domain.ExecutionStep;
import org.example.plan.domain.Intent;
import org.example.plan.domain.Plan;
import org.example.plan.domain.StepResult;
import org.example.tool.ToolRegistry;
import org.springframework.stereotype.Service;

/**
 * 步骤执行器
 * 执行单个步骤并管理数据缓存
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StepExecutor {

    private final ToolRegistry toolRegistry;
    private final DataCache dataCache;

    /**
     * 执行单个步骤
     */
    public StepResult execute(ExecutionStep step, String sessionId) {
        log.info("Executing step: {} (tool={})", step.getAction(), step.getTool());

        step.markRunning();

        try {
            // 解析参数（支持数据别名引用）
            var resolvedParams = resolveParams(step.getParams(), sessionId);

            // 获取工具并执行
            var tool = toolRegistry.getTool(step.getTool());
            var toolResult = tool.execute(resolvedParams, null);

            if (toolResult.isSuccess()) {
                // 存储结果到DataCache
                if (toolResult.getData() != null) {
                    String alias = dataCache.store(sessionId, toolResult.getData());
                    step.markCompleted();
                    return StepResult.success(alias, toolResult.getData());
                } else {
                    step.markCompleted();
                    return StepResult.success(null, null);
                }
            } else {
                step.markFailed();
                return StepResult.failure(toolResult.getError());
            }

        } catch (Exception e) {
            log.error("Step execution failed", e);
            step.markFailed();
            return StepResult.failure(e.getMessage());
        }
    }

    /**
     * 解析参数，支持数据别名引用
     */
    private java.util.Map<String, Object> resolveParams(java.util.Map<String, Object> params, String sessionId) {
        if (params == null) {
            return java.util.Map.of();
        }

        java.util.Map<String, Object> resolved = new java.util.HashMap<>();
        for (var entry : params.entrySet()) {
            Object value = entry.getValue();
            if (value instanceof String && ((String) value).startsWith("${") && ((String) value).endsWith("}")) {
                String alias = ((String) value).substring(2, ((String) value).length() - 1);
                resolved.put(entry.getKey(), dataCache.getBrief(sessionId, alias));
            } else {
                resolved.put(entry.getKey(), value);
            }
        }
        return resolved;
    }
}