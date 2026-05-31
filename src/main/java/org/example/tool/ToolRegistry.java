package org.example.tool;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * 工具注册中心
 * 负责管理和访问所有MCP Tools
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ToolRegistry {

    private final Map<String, Tool> tools = new ConcurrentHashMap<>();

    @PostConstruct
    public void init() {
        log.info("ToolRegistry initialized with {} tools", tools.size());
    }

    /**
     * 注册工具
     */
    public void register(Tool tool) {
        tools.put(tool.getName(), tool);
        log.info("Registered tool: {} - {}", tool.getName(), tool.getDescription());
    }

    /**
     * 获取工具
     */
    public Tool getTool(String name) {
        Tool tool = tools.get(name);
        if (tool == null) {
            throw new ToolNotFoundException("Tool not found: " + name);
        }
        return tool;
    }

    /**
     * 检查工具是否存在
     */
    public boolean hasTool(String name) {
        return tools.containsKey(name);
    }

    /**
     * 获取所有工具
     */
    public List<Tool> getAllTools() {
        return List.copyOf(tools.values());
    }

    /**
     * 获取分类工具
     */
    public List<Tool> getToolsByCategory(String category) {
        return tools.values().stream()
                .filter(t -> t.getCategory().equals(category))
                .collect(Collectors.toList());
    }

    /**
     * 获取工具描述（用于LLM规划）
     */
    public String getToolsDescription() {
        return tools.values().stream()
                .map(t -> String.format("- %s: %s", t.getName(), t.getDescription()))
                .collect(Collectors.joining("\n"));
    }
}