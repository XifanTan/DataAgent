package org.example.plan.llm;

import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import lombok.extern.slf4j.Slf4j;
import org.example.plan.config.LlmConfig;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * LLM网关
 * 统一管理LLM调用，支持多Provider
 */
@Slf4j
@Component
public class LLMGateway {

    private final ChatLanguageModel chatModel;
    private final String defaultModel;

    public LLMGateway(LlmConfig llmConfig) {
        this.defaultModel = llmConfig.getModel();

        // 创建OpenAI兼容的ChatLanguageModel（MiniMax使用OpenAI格式）
        this.chatModel = OpenAiChatModel.builder()
                .baseUrl(llmConfig.getBaseUrl())
                .apiKey(llmConfig.getApiKey())
                .model(llmConfig.getModel())
                .temperature(llmConfig.getTemperature())
                .maxTokens(llmConfig.getMaxTokens())
                .timeout(Duration.ofSeconds(60))
                .build();

        log.info("LLMGateway initialized with model={}, baseUrl={}",
                llmConfig.getModel(), llmConfig.getBaseUrl());
    }

    /**
     * 生成响应
     */
    public String generate(String prompt) {
        log.debug("Generating response for prompt (length={})", prompt.length());
        try {
            String response = chatModel.generate(prompt);
            log.debug("Generated response (length={})", response.length());
            return response;
        } catch (Exception e) {
            log.error("LLM generation failed", e);
            throw new LLMException("Failed to generate response: " + e.getMessage(), e);
        }
    }

    /**
     * 获取默认模型
     */
    public String getDefaultModel() {
        return defaultModel;
    }

    /**
     * LLM异常
     */
    public static class LLMException extends RuntimeException {
        public LLMException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}