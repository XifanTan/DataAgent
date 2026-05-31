package org.example.plan.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Planner配置
 */
@Data
@Configuration
@ConfigurationProperties(prefix = "chatbi.planner")
public class PlannerConfig {
    private Double confidenceThreshold = 0.7;
    private Integer maxHistorySize = 50;
    private Integer maxPlanSteps = 10;
}