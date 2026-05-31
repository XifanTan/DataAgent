package org.example.plan.domain;

import lombok.Builder;
import lombok.Data;

/**
 * 步骤执行结果
 */
@Data
@Builder
public class StepResult {
    private boolean success;
    private String dataAlias;       // 数据别名（存储在DataCache中）
    private Object data;            // 简略数据
    private String error;           // 错误信息

    public static StepResult success(String dataAlias, Object data) {
        return StepResult.builder()
                .success(true)
                .dataAlias(dataAlias)
                .data(data)
                .build();
    }

    public static StepResult failure(String error) {
        return StepResult.builder()
                .success(false)
                .error(error)
                .build();
    }
}