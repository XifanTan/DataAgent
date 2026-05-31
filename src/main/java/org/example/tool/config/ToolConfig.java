package org.example.tool.config;

import lombok.RequiredArgsConstructor;
import org.example.tool.ToolRegistry;
import org.example.tool.impl.MockFormatterTool;
import org.example.tool.impl.MockLogicRecognitionTool;
import org.example.tool.impl.MockMetricValueTool;
import org.springframework.context.annotation.Configuration;

import jakarta.annotation.PostConstruct;

/**
 * 工具配置
 * 自动注册所有MCP工具
 */
@Configuration
@RequiredArgsConstructor
public class ToolConfig {

    private final ToolRegistry toolRegistry;
    private final MockLogicRecognitionTool logicRecognitionTool;
    private final MockMetricValueTool metricValueTool;
    private final MockFormatterTool formatterTool;

    @PostConstruct
    public void registerTools() {
        toolRegistry.register(logicRecognitionTool);
        toolRegistry.register(metricValueTool);
        toolRegistry.register(formatterTool);
    }
}