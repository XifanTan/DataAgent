package org.example.plan.domain;

import lombok.Builder;
import lombok.Data;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 执行计划
 */
@Data
@Builder
public class Plan {
    private String planId;                      // 计划ID
    private String intentId;                      // 关联的意图ID
    private List<ExecutionStep> steps;           // 执行步骤列表
    private PlanStatus status;                    // 计划状态
    private OffsetDateTime createdAt;             // 创建时间

    public enum PlanStatus {
        PENDING,
        RUNNING,
        COMPLETED,
        FAILED
    }

    public static Plan create(String intentId) {
        return Plan.builder()
                .planId(UUID.randomUUID().toString())
                .intentId(intentId)
                .steps(new ArrayList<>())
                .status(PlanStatus.PENDING)
                .createdAt(OffsetDateTime.now())
                .build();
    }

    public void addStep(ExecutionStep step) {
        this.steps.add(step);
    }

    public int getStepCount() {
        return this.steps.size();
    }
}