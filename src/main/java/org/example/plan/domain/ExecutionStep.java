package org.example.plan.domain;

import lombok.Builder;
import lombok.Data;

import java.util.Map;
import java.util.UUID;

/**
 * 执行步骤
 */
@Data
@Builder
public class ExecutionStep {
    private String stepId;                      // 步骤ID
    private int stepOrder;                        // 步骤序号
    private String action;                       // 动作名称
    private String description;                  // 步骤描述
    private String tool;                         // 使用的工具
    private Map<String, Object> params;          // 执行参数
    private StepStatus status;                   // 状态

    public enum StepStatus {
        PENDING,
        RUNNING,
        COMPLETED,
        FAILED,
        SKIPPED
    }

    public static ExecutionStep create(int order, String action, String tool, Map<String, Object> params) {
        return ExecutionStep.builder()
                .stepId(UUID.randomUUID().toString())
                .stepOrder(order)
                .action(action)
                .tool(tool)
                .params(params)
                .status(StepStatus.PENDING)
                .build();
    }

    public void markRunning() {
        this.status = StepStatus.RUNNING;
    }

    public void markCompleted() {
        this.status = StepStatus.COMPLETED;
    }

    public void markFailed() {
        this.status = StepStatus.FAILED;
    }
}