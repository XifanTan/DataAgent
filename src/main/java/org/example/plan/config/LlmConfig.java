package org.example.plan.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * LLM配置
 */
@Data
@Configuration
@ConfigurationProperties(prefix = "chatbi.llm")
public class LlmConfig {
    private String model = "minimax2.7";
    private String apiKey;
    private String baseUrl = "https://api.minimax.chat/v1";
    private Double temperature = 0.7;
    private Integer maxTokens = 2000;
}