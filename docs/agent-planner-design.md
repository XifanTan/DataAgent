# AgentPlanner 组件详细设计

**文档版本**: 1.0.0
**日期**: 2026-05-31
**状态**: 设计中

---

## 1. 概述

### 1.1 组件定位

AgentPlanner是Chat BI平台的核心规划组件，负责理解用户意图并生成可执行的任务计划。它类似于Claude Code的规划器，通过LLM驱动实现智能的任务分解和执行路由。

### 1.2 核心职责

| 职责 | 说明 |
|-----|------|
| **意图理解** | 解析用户自然语言查询，提取关键实体和指标需求 |
| **计划生成** | 基于意图生成多步骤执行计划 |
| **上下文管理** | 维护会话上下文，支持多轮对话 |
| **结果整合** | 聚合多个步骤的执行结果 |

---

## 2. 架构设计

### 2.1 组件结构图

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                              AgentPlanner                                   │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                             │
│  ┌───────────────────┐    ┌───────────────────┐    ┌───────────────────┐  │
│  │   IntentClassifier │    │    PlanGenerator  │    │  ContextManager   │  │
│  │   (意图分类)        │    │   (计划生成)       │    │   (上下文管理)     │  │
│  └───────────────────┘    └───────────────────┘    └───────────────────┘  │
│            │                      │                      │                  │
│            └──────────────────────┼──────────────────────┘                  │
│                                   ▼                                         │
│                    ┌───────────────────────────────┐                       │
│                    │       LLMGateway              │                       │
│                    │   (LLM统一网关)                 │                       │
│                    └───────────────────────────────┘                       │
│                                   │                                         │
│            ┌──────────────────────┼──────────────────────┐                 │
│            ▼                      ▼                      ▼                 │
│  ┌─────────────────┐    ┌─────────────────┐    ┌─────────────────┐       │
│  │  PromptManager  │    │ ResponseParser  │    │  RetryHandler   │       │
│  │  (Prompt管理)   │    │  (响应解析)     │    │  (重试处理)     │       │
│  └─────────────────┘    └─────────────────┘    └─────────────────┘       │
│                                                                            │
│  ┌───────────────────────────────────────────────────────────────────────┐ │
│  │                        DataCache                                      │ │
│  │                   (业务数据缓存)                                        │ │
│  │  - 存储步骤执行产生的业务数据（指标数据/分析结果）                          │ │
│  │  - 用数据别名在上下文中传递引用，而非真实数据                              │ │
│  │  - 支持按需加载完整数据（供Python分析等）                                  │ │
│  └───────────────────────────────────────────────────────────────────────┘ │
│                                                                            │
└─────────────────────────────────────────────────────────────────────────────┘
```

### 2.2 类图 (Class Diagram)

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                              «component»                                    │
│                              AgentPlanner                                   │
├─────────────────────────────────────────────────────────────────────────────┤
│ - chatModel: ChatLanguageModel                                              │
│ - toolRegistry: ToolRegistry                                               │
│ - intentClassifier: IntentClassifier                                        │
│ - planGenerator: PlanGenerator                                             │
│ - contextManager: ContextManager                                           │
│ - llmGateway: LLMGateway                                                   │
│ - dataCache: DataCache                                                      │
├─────────────────────────────────────────────────────────────────────────────┤
│ + understand(userQuery: String): Intent                                    │
│ + plan(intent: Intent): Plan                                                │
│ + execute(plan: Plan, context: SkillContext): Flux<SSEEvent>                │
│ + storeData(alias: String, data: Object): void                              │
│ + getData(alias: String, loadFull: boolean): BusinessData                    │
│ + reset(sessionId: String): void                                           │
└─────────────────────────────────────────────────────────────────────────────┘
                                    │
                                    │ «uses»
                                    ▼
                        ┌─────────────────────────────┐
                        │        DataCache            │
                        │     (业务数据缓存)           │
                        ├─────────────────────────────┤
                        │ - cache: Map<String,        │
                        │   BusinessData>             │
                        │ - maxCacheSize: int         │
                        ├─────────────────────────────┤
                        │ + store(alias, data): void  │
                        │ + get(alias): BusinessData   │
                        │ + getBrief(alias): Object   │
                        │ + getFull(alias): Object    │
                        │ + clear(sessionId): void    │
                        └─────────────────────────────┘

---

## 3. 核心类设计

### 3.1 AgentPlanner 主类

```java
/**
 * AgentPlanner - LLM驱动的规划器核心组件
 *
 * 职责：
 * 1. 协调意图理解和计划生成
 * 2. 管理会话上下文
 * 3. 执行工作流并推送SSE事件
 */
@Service
@Scope("prototype")  // 每个会话一个新实例
public class AgentPlanner {

    private final ChatLanguageModel chatModel;
    private final ToolRegistry toolRegistry;
    private final IntentClassifier intentClassifier;
    private final PlanGenerator planGenerator;
    private final ContextManager contextManager;
    private final LLMGateway llmGateway;

    // 会话级别的状态
    private String currentSessionId;
    private Intent currentIntent;
    private Plan currentPlan;

    /**
     * 理解用户意图
     * @param userQuery 用户查询文本
     * @return Intent 解析后的意图对象
     */
    public Intent understand(String userQuery) {
        // 1. 更新会话上下文
        SessionContext session = contextManager.getOrCreate(currentSessionId);
        session.addUserMessage(userQuery);

        // 2. 使用IntentClassifier进行分类
        Intent intent = intentClassifier.classify(userQuery, session);

        // 3. 提取实体和指标
        intent = intentClassifier.extractEntities(intent, userQuery);

        // 4. 保存当前意图
        this.currentIntent = intent;

        return intent;
    }

    /**
     * 生成执行计划
     * @param intent 已解析的意图
     * @return Plan 执行计划
     */
    public Plan plan(Intent intent) {
        // 1. 获取可用工具列表
        List<Tool> availableTools = toolRegistry.getAvailableTools();

        // 2. 使用PlanGenerator生成计划
        Plan plan = planGenerator.generate(intent, availableTools);

        // 3. 验证计划可行性
        if (!planGenerator.validate(plan)) {
            throw new PlanValidationException("Generated plan is not valid");
        }

        // 4. 保存当前计划
        this.currentPlan = plan;

        return plan;
    }

    /**
     * 流式执行计划
     * @param plan 执行计划
     * @param context 执行上下文
     * @return Flux<SSEEvent> SSE事件流
     */
    public Flux<SSEEvent> execute(Plan plan, SkillContext context) {
        return Flux.create(sink -> {
            try {
                // 通知开始执行
                sink.next(SSEEvent.stepStarted(0, "plan_execution",
                    "开始执行计划，共" + plan.getSteps().size() + "个步骤"));

                // 逐步执行
                for (int i = 0; i < plan.getSteps().size(); i++) {
                    ExecutionStep step = plan.getSteps().get(i);

                    // 推送步骤开始
                    sink.next(SSEEvent.stepStarted(i + 1, step.getAction(),
                        "正在执行: " + step.getDescription()));

                    // 执行步骤
                    StepResult result = executeStep(step, context);

                    // 推送步骤完成
                    sink.next(SSEEvent.stepCompleted(i + 1, step.getAction(), result));

                    // 检查是否需要停止
                    if (result.isFailed() && !result.isRetryable()) {
                        break;
                    }
                }

                // 执行完成
                sink.next(SSEEvent.taskCompleted(context.getSharedData()));
                sink.complete();

            } catch (Exception e) {
                sink.next(SSEEvent.error(e.getMessage()));
                sink.complete();
            }
        });
    }

    private StepResult executeStep(ExecutionStep step, SkillContext context) {
        Tool tool = toolRegistry.getTool(step.getTool());
        return tool.execute(step.getParams(), context.getToolContext());
    }
}
```

### 3.2 IntentClassifier - 意图分类器

```java
/**
 * 意图分类器
 * 负责理解用户查询，提取实体和意图类型
 */
@Service
public class IntentClassifier {

    private final LLMGateway llmGateway;
    private final PromptManager promptManager;
    private final ResponseParser responseParser;

    // 支持的意图类型
    public enum IntentType {
        METRIC_QUERY,      // 指标查询
        COMPARISON,        // 对比分析
        TREND_ANALYSIS,    // 趋势分析
        REPORT_GENERATE,   // 报告生成
        CLARIFICATION      // 需求澄清
    }

    /**
     * 分类用户意图
     */
    public Intent classify(String userQuery, SessionContext session) {
        // 1. 构建分类Prompt
        Prompt prompt = promptManager.buildIntentClassificationPrompt(
            userQuery, session.getHistory());

        // 2. 调用LLM
        String response = llmGateway.generate(prompt);

        // 3. 解析响应
        ClassificationResult result = responseParser.parseIntentClassification(response);

        // 4. 构建Intent对象
        return Intent.builder()
            .intentId(UUID.randomUUID().toString())
            .rawQuery(userQuery)
            .intentType(result.getIntentType())
            .confidence(result.getConfidence())
            .entities(result.getEntities())
            .requiredMetrics(result.getMetrics())
            .build();
    }

    /**
     * 提取实体（地区、时间、指标等）
     */
    public Intent extractEntities(Intent intent, String userQuery) {
        // 使用NER或LLM提取实体
        Prompt prompt = promptManager.buildEntityExtractionPrompt(userQuery);
        String response = llmGateway.generate(prompt);

        Map<String, Object> entities = responseParser.parseEntities(response);
        intent.setEntities(entities);

        return intent;
    }

    /**
     * 检测是否需要澄清用户意图
     */
    public boolean needsClarification(Intent intent) {
        // 置信度低于阈值或缺少必要指标时需要澄清
        return intent.getConfidence() < 0.7
            || intent.getRequiredMetrics().isEmpty();
    }
}
```

### 3.3 PlanGenerator - 计划生成器

```java
/**
 * 计划生成器
 * 基于意图生成可执行的步骤计划
 */
@Service
public class PlanGenerator {

    private final LLMGateway llmGateway;
    private final PromptManager promptManager;
    private final ResponseParser responseParser;
    private final ToolRegistry toolRegistry;

    /**
     * 生成执行计划
     */
    public Plan generate(Intent intent, List<Tool> availableTools) {
        // 1. 获取工具描述
        String toolDescriptions = formatToolsForPrompt(availableTools);

        // 2. 构建生成Prompt
        Prompt prompt = promptManager.buildPlanGenerationPrompt(
            intent, toolDescriptions);

        // 3. 调用LLM生成计划
        String response = llmGateway.generate(prompt);

        // 4. 解析计划
        PlanStructure planStructure = responseParser.parsePlan(response);

        // 5. 转换为Plan对象
        return buildPlan(intent, planStructure);
    }

    /**
     * 验证计划可行性
     */
    public boolean validate(Plan plan) {
        if (plan.getSteps().isEmpty()) {
            return false;
        }

        // 检查每个步骤都有有效的工具
        for (ExecutionStep step : plan.getSteps()) {
            if (!toolRegistry.hasTool(step.getTool())) {
                log.warn("Plan references unknown tool: {}", step.getTool());
                return false;
            }
        }

        return true;
    }

    /**
     * 构建计划对象
     */
    private Plan buildPlan(Intent intent, PlanStructure planStructure) {
        List<ExecutionStep> steps = new ArrayList<>();

        for (int i = 0; i < planStructure.getStepCount(); i++) {
            StepDef stepDef = planStructure.getStep(i);

            ExecutionStep step = ExecutionStep.builder()
                .stepId(UUID.randomUUID().toString())
                .stepOrder(i + 1)
                .action(stepDef.getAction())
                .description(stepDef.getDescription())
                .tool(stepDef.getTool())
                .params(stepDef.getParams())
                .status(StepStatus.PENDING)
                .build();

            steps.add(step);
        }

        return Plan.builder()
            .planId(UUID.randomUUID().toString())
            .intentId(intent.getIntentId())
            .steps(steps)
            .variables(new HashMap<>())
            .createdAt(OffsetDateTime.now())
            .build();
    }

    private String formatToolsForPrompt(List<Tool> tools) {
        return tools.stream()
            .map(t -> String.format("- %s: %s (params: %s)",
                t.getName(), t.getDescription(), t.getParametersSchema()))
            .collect(Collectors.joining("\n"));
    }
}
```

### 3.4 ContextManager - 上下文管理器

```java
/**
 * 上下文管理器
 * 维护会话历史和上下文状态
 */
@Service
public class ContextManager {

    private final Map<String, SessionContext> sessions = new ConcurrentHashMap<>();
    private final int maxHistorySize = 50;

    /**
     * 获取或创建会话上下文
     */
    public SessionContext getOrCreate(String sessionId) {
        return sessions.computeIfAbsent(sessionId, k -> {
            SessionContext context = new SessionContext();
            context.setSessionId(sessionId);
            return context;
        });
    }

    /**
     * 获取会话上下文
     */
    public Optional<SessionContext> get(String sessionId) {
        return Optional.ofNullable(sessions.get(sessionId));
    }

    /**
     * 更新会话上下文
     */
    public void update(String sessionId, SessionContext context) {
        sessions.put(sessionId, context);
        maintainHistorySize(sessionId);
    }

    /**
     * 清除会话上下文
     */
    public void clear(String sessionId) {
        sessions.remove(sessionId);
    }

    /**
     * 维护历史大小
     */
    private void maintainHistorySize(String sessionId) {
        SessionContext context = sessions.get(sessionId);
        if (context != null && context.getHistory().size() > maxHistorySize) {
            // 保留最近的消息
            List<ChatMessage> recentMessages = context.getHistory().stream()
                .skip(context.getHistory().size() - maxHistorySize)
                .collect(Collectors.toList());
            context.setHistory(recentMessages);
        }
    }
}

/**
 * 会话上下文
 */
@Data
public class SessionContext {
    private String sessionId;
    private List<ChatMessage> history = new ArrayList<>();
    private Map<String, Object> attributes = new HashMap<>();
    private Intent lastIntent;
    private Plan lastPlan;
    private OffsetDateTime createdAt;
    private OffsetDateTime lastAccessedAt;

    public void addUserMessage(String message) {
        this.history.add(ChatMessage.user(message));
        this.lastAccessedAt = OffsetDateTime.now();
    }

    public void addAssistantMessage(String message) {
        this.history.add(ChatMessage.assistant(message));
        this.lastAccessedAt = OffsetDateTime.now();
    }
}
```

### 3.5 LLMGateway - LLM网关

```java
/**
 * LLM网关
 * 统一管理LLM调用，支持多Provider
 */
@Service
public class LLMGateway {

    private final Map<String, ChatLanguageModel> models = new HashMap<>();
    private final PromptManager promptManager;
    private final RetryHandler retryHandler;
    private final String defaultModel;

    /**
     * 注册模型
     */
    public void registerModel(String modelId, ChatLanguageModel model) {
        models.put(modelId, model);
    }

    /**
     * 生成响应
     */
    public String generate(Prompt prompt) {
        return generate(prompt, defaultModel);
    }

    /**
     * 指定模型生成响应
     */
    public String generate(Prompt prompt, String modelId) {
        ChatLanguageModel model = models.get(modelId);
        if (model == null) {
            throw new LLMException("Model not found: " + modelId);
        }

        // 使用重试处理器
        return retryHandler.executeWithRetry(() -> {
            ChatResponse response = model.generate(
                UserMessage.from(prompt.getTemplate())
            );
            return response.getContent();
        });
    }

    /**
     * 流式生成响应
     */
    public Flux<String> generateStreaming(Prompt prompt) {
        return generateStreaming(prompt, defaultModel);
    }

    /**
     * 指定模型流式生成响应
     */
    public Flux<String> generateStreaming(Prompt prompt, String modelId) {
        ChatLanguageModel model = models.get(modelId);
        if (model == null) {
            throw new LLMException("Model not found: " + modelId);
        }

        return Flux.create(sink -> {
            model.generateStreaming(
                UserMessage.from(prompt.getTemplate()),
                new ChatResponseCallback() {
                    @Override
                    public void onChunk(String chunk) {
                        sink.next(chunk);
                    }

                    @Override
                    public void onComplete() {
                        sink.complete();
                    }

                    @Override
                    public void onError(Throwable t) {
                        sink.error(t);
                    }
                }
            );
        });
    }

    /**
     * 检查是否支持某模型
     */
    public boolean supports(String modelId) {
        return models.containsKey(modelId);
    }

    /**
     * 获取默认模型
     */
    public String getDefaultModel() {
        return defaultModel;
    }
}
```

### 3.6 PromptManager - Prompt管理器

```java
/**
 * Prompt管理器
 * 管理和构建各类Prompt模板
 */
@Service
public class PromptManager {

    // 意图分类Prompt模板
    private static final String INTENT_CLASSIFICATION_TEMPLATE = """
        你是一个数据分析助手。请分析以下用户查询，识别用户的意图类型。

        用户查询: {query}

        可选的意图类型:
        - METRIC_QUERY: 查询特定指标（如销售额、用户数）
        - COMPARISON: 对比分析（如对比不同地区/时间段）
        - TREND_ANALYSIS: 趋势分析（如分析增长趋势）
        - REPORT_GENERATE: 生成报告
        - CLARIFICATION: 需要澄清需求

        请以JSON格式返回:
        {
          "intentType": "类型",
          "confidence": 0.0-1.0,
          "reasoning": "判断理由"
        }
        """;

    // 实体提取Prompt模板
    private static final String ENTITY_EXTRACTION_TEMPLATE = """
        从以下文本中提取关键实体信息:

        文本: {query}

        提取以下类型的实体:
        - 地区/部门
        - 时间范围
        - 指标名称
        - 聚合方式（求和、平均、计数）

        返回JSON格式:
        {
          "region": "地区（如华东、华南）",
          "timeRange": "时间范围（如最近7天、上月）",
          "metrics": ["指标1", "指标2"],
          "aggregations": {"指标1": "sum"}
        }
        """;

    // 计划生成Prompt模板
    private static final String PLAN_GENERATION_TEMPLATE = """
        基于以下意图和可用工具，生成执行计划。

        用户意图:
        {intent}

        可用的工具:
        {tools}

        请生成步骤计划，返回JSON数组:
        [
          {
            "action": "步骤动作名称",
            "description": "步骤描述",
            "tool": "使用的工具名",
            "params": {"参数": "值"}
          }
        ]

        约束:
        - 每个步骤必须使用一个工具
        - 步骤之间要有依赖关系时使用上一步的结果
        - 尽量将复杂查询分解为多个简单步骤
        """;

    /**
     * 构建意图分类Prompt
     */
    public Prompt buildIntentClassificationPrompt(String query, List<ChatMessage> history) {
        String template = INTENT_CLASSIFICATION_TEMPLATE
            .replace("{query}", query);

        // 加入历史上下文
        if (!history.isEmpty()) {
            template = "最近对话历史:\n" + formatHistory(history) + "\n\n" + template;
        }

        return Prompt.builder()
            .template(template)
            .variables(Map.of("query", query))
            .build();
    }

    /**
     * 构建实体提取Prompt
     */
    public Prompt buildEntityExtractionPrompt(String query) {
        return Prompt.builder()
            .template(ENTITY_EXTRACTION_TEMPLATE)
            .variables(Map.of("query", query))
            .build();
    }

    /**
     * 构建计划生成Prompt
     */
    public Prompt buildPlanGenerationPrompt(Intent intent, String toolDescriptions) {
        return Prompt.builder()
            .template(PLAN_GENERATION_TEMPLATE)
            .variables(Map.of(
                "intent", intent.toJson(),
                "tools", toolDescriptions
            ))
            .build();
    }

    private String formatHistory(List<ChatMessage> history) {
        return history.stream()
            .map(m -> m.getRole() + ": " + m.getContent())
            .collect(Collectors.joining("\n"));
    }
}
```

### 3.7 ResponseParser - 响应解析器

```java
/**
 * 响应解析器
 * 解析LLM返回的响应
 */
@Service
public class ResponseParser {

    private final ObjectMapper objectMapper;

    /**
     * 解析意图分类结果
     */
    public ClassificationResult parseIntentClassification(String response) {
        try {
            JsonNode json = objectMapper.readTree(response);
            return ClassificationResult.builder()
                .intentType(IntentClassifier.IntentType.valueOf(
                    json.get("intentType").asText()))
                .confidence(json.get("confidence").asDouble())
                .reasoning(json.get("reasoning").asText())
                .build();
        } catch (Exception e) {
            // 降级处理
            return ClassificationResult.builder()
                .intentType(IntentClassifier.IntentType.METRIC_QUERY)
                .confidence(0.5)
                .reasoning("Parse failed, default to METRIC_QUERY")
                .build();
        }
    }

    /**
     * 解析实体提取结果
     */
    public Map<String, Object> parseEntities(String response) {
        try {
            return objectMapper.readValue(response,
                new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            log.warn("Failed to parse entities: {}", response);
            return Map.of();
        }
    }

    /**
     * 解析计划结果
     */
    public PlanStructure parsePlan(String response) {
        try {
            JsonNode json = objectMapper.readTree(response);
            List<StepDef> steps = new ArrayList<>();

            JsonNode stepsNode = json.get("steps");
            if (stepsNode != null && stepsNode.isArray()) {
                for (JsonNode stepNode : stepsNode) {
                    StepDef step = StepDef.builder()
                        .action(stepNode.get("action").asText())
                        .description(stepNode.has("description")
                            ? stepNode.get("description").asText() : "")
                        .tool(stepNode.get("tool").asText())
                        .params(parseParams(stepNode.get("params")))
                        .build();
                    steps.add(step);
                }
            }

            return new PlanStructure(steps);
        } catch (Exception e) {
            throw new ParseException("Failed to parse plan: " + response, e);
        }
    }

    private Map<String, Object> parseParams(JsonNode paramsNode) {
        if (paramsNode == null || paramsNode.isNull()) {
            return Map.of();
        }
        return objectMapper.convertValue(paramsNode,
            new TypeReference<Map<String, Object>>() {});
    }
}
```

### 3.8 RetryHandler - 重试处理器

```java
/**
 * 重试处理器
 * 处理LLM调用的重试逻辑
 */
@Service
public class RetryHandler {

    private static final int DEFAULT_MAX_RETRIES = 3;
    private static final long DEFAULT_BACKOFF_MS = 1000;

    private final int maxRetries;
    private final long backoffMs;

    public RetryHandler() {
        this(DEFAULT_MAX_RETRIES, DEFAULT_BACKOFF_MS);
    }

    public RetryHandler(int maxRetries, long backoffMs) {
        this.maxRetries = maxRetries;
        this.backoffMs = backoffMs;
    }

    /**
     * 执行带重试的操作
     */
    public <T> T executeWithRetry(Supplier<T> operation) {
        int attempts = 0;
        Exception lastException = null;

        while (attempts < maxRetries) {
            try {
                return operation.get();
            } catch (Exception e) {
                lastException = e;
                attempts++;

                if (attempts >= maxRetries || !isRetryable(e)) {
                    throw new LLMException("Max retries exceeded", e);
                }

                // 指数退避
                long delay = backoffMs * (long) Math.pow(2, attempts - 1);
                try {
                    Thread.sleep(delay);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new LLMException("Retry interrupted", ie);
                }

                log.warn("Retry attempt {} after error: {}",
                    attempts, e.getMessage());
            }
        }

        throw new LLMException("Max retries exceeded", lastException);
    }

    /**
     * 判断是否可重试
     */
    private boolean isRetryable(Exception e) {
        // 网络错误、超时可重试
        // 权限错误、参数错误不可重试
        if (e instanceof HttpTimeoutException ||
            e.getMessage().contains("timeout") ||
            e.getMessage().contains("network")) {
            return true;
        }
        return false;
    }
}
```

### 3.9 DataCache - 业务数据缓存

```java
/**
 * DataCache - 业务数据缓存
 *
 * 核心功能：
 * 1. 存储步骤执行产生的业务数据（指标数据、分析结果等）
 * 2. 用数据别名在上下文中传递引用，而非真实数据
 * 3. 支持按需加载完整数据（供Python分析等heavy操作使用）
 *
 * 设计原则：
 * - 业务数据与执行上下文分离，避免上下文膨胀
 * - 按需加载完整数据，避免不必要的内存占用
 * - 自动管理缓存生命周期
 */
@Service
public class DataCache {

    // 会话级别缓存: sessionId -> (alias -> BusinessData)
    private final Map<String, Map<String, BusinessData>> sessionCaches = new ConcurrentHashMap<>();
    private final int maxCacheSize = 100;

    /**
     * 存储业务数据
     * @param sessionId 会话ID
     * @param alias 数据别名（唯一标识）
     * @param data 原始业务数据
     */
    public void store(String sessionId, String alias, Object data) {
        BusinessData businessData = BusinessData.create(alias, data);
        getOrCreateSessionCache(sessionId).put(alias, businessData);
    }

    /**
     * 获取业务数据
     * @param alias 数据别名
     * @return BusinessData（包含简略结果和完整结果引用）
     */
    public BusinessData get(String sessionId, String alias) {
        Map<String, BusinessData> cache = sessionCaches.get(sessionId);
        if (cache == null) {
            throw new DataNotFoundException("Session not found: " + sessionId);
        }
        BusinessData data = cache.get(alias);
        if (data == null) {
            throw new DataNotFoundException("Data alias not found: " + alias);
        }
        return data;
    }

    /**
     * 仅获取简略结果（用于显示和轻量级传递）
     * 不加载完整数据，节省内存
     */
    public Object getBrief(String sessionId, String alias) {
        return get(sessionId, alias).getBrief();
    }

    /**
     * 获取完整数据（用于需要完整数据的操作如Python分析）
     * 懒加载：仅在明确请求时加载完整数据
     */
    public Object getFull(String sessionId, String alias) {
        BusinessData data = get(sessionId, alias);
        return data.getFullData();
    }

    /**
     * 检查数据是否存在
     */
    public boolean has(String sessionId, String alias) {
        Map<String, BusinessData> cache = sessionCaches.get(sessionId);
        return cache != null && cache.containsKey(alias);
    }

    /**
     * 清除会话的所有缓存数据
     */
    public void clear(String sessionId) {
        sessionCaches.remove(sessionId);
    }

    /**
     * 获取会话缓存，如果不存在则创建
     */
    private Map<String, BusinessData> getOrCreateSessionCache(String sessionId) {
        return sessionCaches.computeIfAbsent(sessionId, k -> new ConcurrentHashMap<>());
    }

    /**
     * 获取缓存统计信息
     */
    public CacheStats getStats(String sessionId) {
        Map<String, BusinessData> cache = sessionCaches.get(sessionId);
        if (cache == null) {
            return new CacheStats(0, 0, 0);
        }
        long totalSize = cache.values().stream()
            .mapToLong(BusinessData::getSize)
            .sum();
        return new CacheStats(cache.size(), totalSize, cache.size());
    }
}

/**
 * BusinessData - 业务数据封装
 *
 * 包含：
 * - alias: 数据别名
 * - brief: 简略结果（用于显示和传递）
 * - fullData: 完整数据（按需加载）
 * - metadata: 元数据（类型、来源、创建时间等）
 */
@Data
public class BusinessData {
    private String alias;                    // 数据别名（唯一标识）
    private String dataType;                 // 数据类型：metric/analyze/result
    private Object brief;                    // 简略结果（用于显示）
    private Object fullData;                 // 完整数据（按需加载，lazy）
    private Map<String, Object> metadata;    // 元数据
    private long createdAt;                  // 创建时间
    private long size;                       // 数据大小（字节）

    /**
     * 创建 BusinessData
     */
    public static BusinessData create(String alias, Object data) {
        Object brief = generateBrief(data);
        return BusinessData.builder()
            .alias(alias)
            .dataType(determineDataType(data))
            .brief(brief)
            .fullData(data)  // 实际实现中应该是lazy加载
            .metadata(extractMetadata(data))
            .createdAt(System.currentTimeMillis())
            .size(estimateSize(data))
            .build();
    }

    /**
     * 生成简略结果
     * 用于显示和轻量级传递，不包含完整数据
     */
    private static Object generateBrief(Object data) {
        if (data instanceof Map) {
            Map<?, ?> map = (Map<?, ?>) data;
            // 简略结果：只保留前几条和汇总信息
            Map<String, Object> brief = new HashMap<>();
            brief.put("_brief", true);
            brief.put("_count", map.size());

            // 如果是列表数据，只保留前3条
            if (map.containsKey("rows") && map.get("rows") instanceof List) {
                List<?> rows = (List<?>) map.get("rows");
                brief.put("rows", rows.stream().limit(3).collect(Collectors.toList()));
                brief.put("totalRows", rows.size());
            }
            return brief;
        }
        return data;
    }

    /**
     * 确定数据类型
     */
    private static String determineDataType(Object data) {
        if (data instanceof Map) {
            Map<?, ?> map = (Map<?, ?>) data;
            if (map.containsKey("metric") || map.containsKey("value")) {
                return "metric";
            } else if (map.containsKey("trend") || map.containsKey("change")) {
                return "analyze";
            }
        }
        return "unknown";
    }

    /**
     * 提取元数据
     */
    private static Map<String, Object> extractMetadata(Object data) {
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("dataType", determineDataType(data));
        metadata.put("createdAt", System.currentTimeMillis());
        return metadata;
    }

    /**
     * 估算数据大小
     */
    private static long estimateSize(Object data) {
        try {
            return objectMapper.writeValueAsBytes(data).length;
        } catch (Exception e) {
            return 0;
        }
    }

    /**
     * 获取完整数据
     * 在需要时从缓存或原始源加载
     */
    public Object getFullData() {
        return fullData;
    }
}

/**
 * 数据未找到异常
 */
public class DataNotFoundException extends RuntimeException {
    public DataNotFoundException(String message) {
        super(message);
    }
}

/**
 * 缓存统计信息
 */
public record CacheStats(int entryCount, long totalSizeBytes, int sessionCount) {}
```

### 3.10 StepExecutor - 步骤执行器（支持数据别名）

```java
/**
 * StepExecutor - 步骤执行器
 *
 * 支持通过数据别名引用业务数据，而非直接传递数据
 */
@Service
public class StepExecutor {

    private final ToolRegistry toolRegistry;
    private final EventPublisher eventPublisher;
    private final DataCache dataCache;

    /**
     * 执行单个步骤并推送SSE事件
     */
    public StepResult execute(ExecutionStep step, SkillContext context) {
        // 1. 推送步骤开始
        eventPublisher.publishStepStarted(step);

        try {
            // 2. 解析参数（支持数据别名引用）
            Map<String, Object> resolvedParams = resolveParams(step.getParams(), context);

            // 3. 获取Tool并执行
            Tool tool = toolRegistry.getTool(step.getTool());
            ToolResult result = tool.execute(resolvedParams, context.getToolContext());

            // 4. 如果有业务数据，存储到DataCache
            if (result.getData() != null) {
                String alias = generateDataAlias(step);
                dataCache.store(context.getSessionId(), alias, result.getData());
                // 在上下文中存储别名而非真实数据
                context.putDataAlias(step.getAction(), alias);
            }

            // 5. 推送步骤完成
            eventPublisher.publishStepCompleted(step, result);

            return StepResult.success(result.getData());

        } catch (Exception e) {
            // 6. 推送失败事件
            eventPublisher.publishStepFailed(step, e);

            if (isRecoverable(e)) {
                return retry(step, context);
            }

            return StepResult.failure(e);
        }
    }

    /**
     * 解析参数，支持数据别名引用
     * 如果参数值是 ${alias} 格式，则从DataCache获取
     */
    private Map<String, Object> resolveParams(Map<String, Object> params, SkillContext context) {
        Map<String, Object> resolved = new HashMap<>();
        for (Map.Entry<String, Object> entry : params.entrySet()) {
            Object value = entry.getValue();
            if (value instanceof String && ((String) value).startsWith("${") && ((String) value).endsWith("}")) {
                String alias = ((String) value).substring(2, ((String) value).length() - 1);
                // 从DataCache获取简略结果
                resolved.put(entry.getKey(), dataCache.getBrief(context.getSessionId(), alias));
            } else {
                resolved.put(entry.getKey(), value);
            }
        }
        return resolved;
    }

    /**
     * 生成数据别名
     */
    private String generateDataAlias(ExecutionStep step) {
        return step.getAction() + "_" + step.getStepOrder() + "_" + System.currentTimeMillis();
    }

    /**
     * 获取完整数据（用于heavy操作）
     */
    public Object getFullData(String sessionId, String alias) {
        return dataCache.getFull(sessionId, alias);
    }
}
```

---

## 4. 数据模型

### 4.1 Intent 类图

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                              «model»                                         │
│                              Intent                                          │
├─────────────────────────────────────────────────────────────────────────────┤
│ - intentId: String                                                          │
│ - rawQuery: String                                                          │
│ - understoodQuery: String                                                   │
│ - intentType: IntentType                                                    │
│ - entities: Map<String, Object>                                             │
│ - requiredMetrics: List<String>                                             │
│ - confidence: double                                                         │
│ - context: Map<String, Object>                                               │
├─────────────────────────────────────────────────────────────────────────────┤
│ + getIntentId(): String                                                      │
│ + getIntentType(): IntentType                                               │
│ + getRequiredMetrics(): List<String>                                         │
│ + getConfidence(): double                                                    │
│ + getEntity(key: String): Object                                             │
│ + setEntities(entities: Map<String, Object>): void                          │
└─────────────────────────────────────────────────────────────────────────────┘
                                      │
                                      │ «uses»
                                      ▼
                    ┌─────────────────────────────────┐
                    │         IntentType             │
                    │        «enumeration»            │
                    ├─────────────────────────────────┤
                    │ METRIC_QUERY                    │
                    │ COMPARISON                      │
                    │ TREND_ANALYSIS                  │
                    │ REPORT_GENERATE                 │
                    │ CLARIFICATION                   │
                    └─────────────────────────────────┘
```

### 4.2 Plan 类图

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                              «model»                                         │
│                               Plan                                           │
├─────────────────────────────────────────────────────────────────────────────┤
│ - planId: String                                                            │
│ - intentId: String                                                          │
│ - steps: List<ExecutionStep>                                                │
│ - variables: Map<String, Object>                                            │
│ - status: PlanStatus                                                        │
│ - createdAt: OffsetDateTime                                                 │
├─────────────────────────────────────────────────────────────────────────────┤
│ + getPlanId(): String                                                        │
│ + getSteps(): List<ExecutionStep>                                            │
│ + getStepCount(): int                                                       │
│ + getStep(stepOrder: int): ExecutionStep                                     │
│ + addVariable(key: String, value: Object): void                              │
│ + getVariable(key: String): Object                                            │
│ + isComplete(): boolean                                                      │
└─────────────────────────────────────────────────────────────────────────────┘
                                      │
                                      │ «contains»
                                      ▼
┌─────────────────────────────────────────────────────────────────────────────┐
│                              «model»                                         │
│                           ExecutionStep                                      │
├─────────────────────────────────────────────────────────────────────────────┤
│ - stepId: String                                                            │
│ - stepOrder: int                                                            │
│ - action: String                                                            │
│ - description: String                                                       │
│ - tool: String                                                              │
│ - params: Map<String, Object>                                                │
│ - status: StepStatus                                                        │
│ - result: StepResult                                                        │
│ - retryCount: int                                                           │
├─────────────────────────────────────────────────────────────────────────────┤
│ + getStepId(): String                                                        │
│ + getTool(): String                                                          │
│ + getParams(): Map<String, Object>                                           │
│ + getStatus(): StepStatus                                                    │
│ + markRunning(): void                                                        │
│ + markCompleted(result: StepResult): void                                    │
│ + markFailed(error: String): void                                            │
│ + canRetry(): boolean                                                        │
└─────────────────────────────────────────────────────────────────────────────┘

                    ┌─────────────────────────────────┐
                    │         StepStatus              │
                    │        «enumeration»            │
                    ├─────────────────────────────────┤
                    │ PENDING                         │
                    │ RUNNING                         │
                    │ COMPLETED                       │
                    │ FAILED                          │
                    │ SKIPPED                         │
                    └─────────────────────────────────┘
```

### 4.3 组件关系图

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                              «component»                                     │
│                           AgentPlanner                                       │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                             │
│  ┌─────────────────┐   ┌─────────────────┐   ┌─────────────────┐         │
│  │ IntentClassifier│   │  PlanGenerator  │   │  ContextManager │         │
│  └────────┬────────┘   └────────┬────────┘   └────────┬────────┘         │
│           │                      │                     │                  │
│           └──────────────────────┼─────────────────────┘                  │
│                                 ▼                                           │
│                    ┌─────────────────────────┐                            │
│                    │       LLMGateway         │                            │
│                    └────────┬────────┬────────┘                            │
│                             │         │                                    │
│                    ┌────────┘         └────────┐                           │
│                    ▼                           ▼                           │
│         ┌─────────────────┐       ┌─────────────────┐                     │
│         │  PromptManager  │       │  ResponseParser  │                     │
│         └─────────────────┘       └─────────────────┘                     │
│                                                                             │
└─────────────────────────────────────────────────────────────────────────────┘
                                        │
                                        │ «creates»
                                        ▼
                    ┌─────────────────────────────────────┐
                    │            «model»                   │
                    │              Intent                  │
                    └─────────────────────────────────────┘
                                        │
                                        │ «results in»
                                        ▼
                    ┌─────────────────────────────────────┐
                    │             «model»                  │
                    │               Plan                   │
                    └─────────────────────────────────────┘
                                        │
                                        │ «contains»
                                        ▼
                    ┌─────────────────────────────────────┐
                    │          «model»                     │
                    │       ExecutionStep                  │
                    └─────────────────────────────────────┘
                                        │
                                        │ «executed by»
                                        ▼
                    ┌─────────────────────────────────────┐
                    │        «component»                  │
                    │            Tool                      │
                    └─────────────────────────────────────┘
```

---

## 5. 执行流程时序图

### 5.1 完整执行流程

```
┌─────────┐    ┌──────────────┐    ┌───────────────┐    ┌────────────┐    ┌─────────┐
│  User   │    │AgentPlanner  │    │IntentClassifier│   │PlanGenerator│   │  Tool   │
└────┬────┘    └───────┬──────┘    └───────┬───────┘    └──────┬─────┘    └────┬────┘
     │                 │                  │                    │               │
     │ "华东区7天销售额"│                  │                    │               │
     │────────────────►│                  │                    │               │
     │                 │                  │                    │               │
     │                 │ understand()     │                    │               │
     │                 │────────────────►│                    │               │
     │                 │                  │                    │               │
     │                 │                  │ classify()        │               │
     │                 │                  │ ──────────────────►│               │
     │                 │                  │                    │               │
     │                 │                  │    Intent          │               │
     │                 │◄─────────────────│◄────────────────────│               │
     │                 │                  │                    │               │
     │                 │ plan(intent)     │                    │               │
     │                 │──────────────────────────────────────►│               │
     │                 │                  │                    │               │
     │                 │                  │         generatePlan()             │
     │                 │                  │                    │──────────────►│
     │                 │                  │                    │               │
     │                 │                  │                    │    Plan       │
     │                 │◄───────────────────────────────────────│◄──────────────│
     │                 │                  │                    │               │
     │                 │ execute(plan)   │                    │               │
     │                 │─────┬───────────│                    │               │
     │                 │     │          │                    │               │
     │     SSE: step_started (1, understanding)                │               │
     │◄────────────────┼─────┘          │                    │               │
     │                 │                │                    │               │
     │     SSE: step_started (2, fetch_sales)                 │               │
     │◄────────────────┼───────────────┼────────────────────┘               │
     │                 │                │                    │               │
     │                 │ executeStep()  │                    │               │
     │                 │──────────────────────────────────────────────►│      │
     │                 │                │                    │               │
     │                 │                │                    │      ToolResult
     │                 │◄───────────────────────────────────────────────│      │
     │     SSE: api_response (tool=metric_api, records=156)              │      │
     │◄────────────────┼───────────────────────────────────────────────┼──────┘
     │                 │                │                    │               │
     │     SSE: task_completed (status=SUCCESS, data={...})             │               │
     │◄────────────────┼────────────────┼────────────────────┼───────────────┘
     │                 │                │                    │               │
```

### 5.2 意图理解时序

```
┌──────────────┐    ┌──────────────────┐    ┌─────────────┐    ┌─────────────┐
│AgentPlanner  │    │IntentClassifier  │    │LLMGateway   │    │PromptManager│
└──────┬───────┘    └────────┬─────────┘    └──────┬──────┘    └──────┬──────┘
       │                     │                    │                  │
       │ classify(query)     │                    │                  │
       │───────────────────►│                    │                  │
       │                     │                    │                  │
       │                     │ buildPrompt()     │                  │
       │                     │────────────────────│                  │
       │                     │                   │◄──────────────────│
       │                     │                   │                  │
       │                     │ Prompt           │                  │
       │                     │◄──────────────────│                  │
       │                     │                   │                  │
       │                     │ generate(prompt)  │                  │
       │                     │───────────────────►                  │
       │                     │                   │                  │
       │                     │    "意图类型..."   │                  │
       │                     │◄───────────────────│                  │
       │                     │                   │                  │
       │                     │ parseResult()     │                  │
       │                     │                   │                  │
       │  Intent             │                   │                  │
       │◄────────────────────│                   │                  │
       │                     │                   │                  │
       │ extractEntities()  │                   │                  │
       │────────────────────►│                   │                  │
       │                     │                   │                  │
       │                     │ buildEntityPrompt │                  │
       │                     │─ ─ ─ ─ ─ ─ ─ ─ ─ ►│                  │
       │                     │                   │                  │
       │                     │    entities       │                  │
       │                     │◄──────────────────│                  │
       │                     │                   │                  │
       │  Intent(entities)  │                   │                  │
       │◄────────────────────│                   │                  │
       │                     │                   │                  │
```

---

## 6. 配置说明

### 6.1 LLM配置

```yaml
# application.yml
chatbi:
  llm:
    default-model: gpt-4o
    models:
      gpt-4o:
        provider: openai
        api-key: ${OPENAI_API_KEY}
        base-url: https://api.openai.com/v1
        temperature: 0.7
        max-tokens: 2000
      claude:
        provider: anthropic
        api-key: ${ANTHROPIC_API_KEY}
        base-url: https://api.anthropic.com
        temperature: 0.7
        max-tokens: 2000

  planner:
    intent:
      confidence-threshold: 0.7
      max-history-size: 50
    plan:
      max-steps: 10
      validate-before-execute: true
    retry:
      max-attempts: 3
      backoff-ms: 1000
```

---

## 7. 错误处理

### 7.1 异常类型

| 异常类型 | 说明 | 处理方式 |
|---------|------|---------|
| `LLMException` | LLM调用失败 | 重试或降级 |
| `IntentClassificationException` | 意图分类失败 | 使用默认类型 |
| `PlanValidationException` | 计划验证失败 | 重新生成或返回错误 |
| `StepExecutionException` | 步骤执行失败 | 重试或跳过 |
| `ParseException` | 响应解析失败 | 重试或使用默认值 |

### 7.2 降级策略

```
LLM调用失败
    │
    ├── 次数 < 3: 重试
    │
    └── 次数 >= 3:
            │
            ├── 有缓存结果: 使用缓存
            │
            └── 无缓存: 返回错误，要求澄清
```

---

## 8. 实现清单

### Phase 1: 核心类实现
- [ ] AgentPlanner 主类
- [ ] IntentClassifier
- [ ] PlanGenerator
- [ ] ContextManager

### Phase 2: LLM集成
- [ ] LLMGateway
- [ ] PromptManager
- [ ] ResponseParser
- [ ] RetryHandler

### Phase 3: 测试完善
- [ ] 单元测试
- [ ] 集成测试

---

*文档版本: 1.0.0*
*创建日期: 2026-05-31*