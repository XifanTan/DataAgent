# Chat BI 数据分析平台设计方案

**项目名称**: Chat BI (对话式数据分析平台)
**版本**: 1.0.0
**日期**: 2026-05-18
**状态**: 设计中

---

## 1. 项目概述

### 1.1 背景与目标

构建一个**对话式数据分析平台**，让用户通过自然语言查询自定义指标服务API，实时获取数据分析结果。平台参考 OpenClaw 和 Claude Code 的规划执行理念，每一步执行过程都通过 SSE 推送反馈给用户前端。

### 1.2 核心需求

| 需求 | 说明 |
|-----|------|
| **数据源** | 自定义指标查询服务 API（非直接 SQL） |
| **交互形式** | 对话式交互，用户自然语言提问 |
| **实时反馈** | 每一步执行过程通过 SSE 推送 |
| **LLM 驱动** | Planner Agent 理解意图并分解任务 |

---

## 2. 系统架构

### 2.1 整体架构图

```
┌─────────────────────────────────────────────────────────────────────────┐
│                           用户前端 (SSE 连接)                             │
│                      chat UI / Dashboard / CLI                           │
└─────────────────────────────────────────────────────────────────────────┘
                                  △
                                  │ SSE Events (text/event-stream)
                                  │
┌─────────────────────────────────────────────────────────────────────────┐
│                        Agent Gateway (Spring Boot)                       │
├─────────────────────────────────────────────────────────────────────────┤
│                                                                          │
│  ┌────────────────┐  ┌──────────────────┐  ┌────────────────────────┐   │
│  │ SSE Controller │  │ StreamingExecutor│  │  EventPublisher         │   │
│  │ /api/chat      │  │                 │  │  (每步骤实时推送)       │   │
│  └────────────────┘  └──────────────────┘  └────────────────────────┘   │
│          │                    │                                          │
│  ┌───────▼────────────────────▼──────────────────────────────────────┐   │
│  │                    Workflow Engine                                 │   │
│  │  ┌────────────────┐  ┌────────────────┐  ┌────────────────────┐ │   │
│  │  │  Planner       │  │   Router       │  │  StepExecutor      │ │   │
│  │  │  Agent         │  │  (Skill路由)   │  │  (步骤执行器)      │ │   │
│  │  │  LLM驱动       │  │                │  │                    │ │   │
│  │  └────────────────┘  └────────────────┘  └────────────────────┘ │   │
│  │                                                              │     │   │
│  │  ┌──────────────────────────────────────────────────────────┐ │     │   │
│  │  │                    Skills Layer                          │ │     │   │
│  │  │  ┌─────────────┐  ┌─────────────┐  ┌─────────────┐      │ │     │   │
│  │  │  │ MetricSkill│  │AnalyzeSkill│  │ FormatSkill │ ...  │ │     │   │
│  │  │  │ 指标查询   │  │ 数据分析   │  │ 格式化     │      │ │     │   │
│  │  │  └─────────────┘  └─────────────┘  └─────────────┘      │ │     │   │
│  │  └──────────────────────────────────────────────────────────┘ │     │   │
│  └────────────────────────────────────────────────────────────────┘   │
│                              │                                            │
│  ┌───────────────────────────▼────────────────────────────────────────┐   │
│  │                    Tool Layer (MCP Tools)                          │   │
│  │  ┌────────────────┐  ┌────────────────┐  ┌────────────────────────┐│   │
│  │  │ MetricAPITool  │  │ DataFrameTool │  │ FormatterTool          ││   │
│  │  │ (指标服务API) │  │ (数据处理)   │  │ (结果格式化)           ││   │
│  │  └────────────────┘  └────────────────┘  └────────────────────────┘│   │
│  └────────────────────────────────────────────────────────────────────────┘   │
│                                                                          │
│  ┌────────────────────────────────────────────────────────────────────────┐│
│  │                      External Services                                  ││
│  │  ┌────────────────────────┐  ┌────────────────────────────────────┐  ││
│  │  │  指标查询服务 API       │  │  LLM Provider (OpenAI / Anthropic) │  ││
│  │  │  custom-metrics-api    │  │                                    │  ││
│  │  └────────────────────────┘  └────────────────────────────────────┘  ││
│  └────────────────────────────────────────────────────────────────────────┘│
└─────────────────────────────────────────────────────────────────────────┘
```

### 2.2 核心组件职责

| 组件 | 职责 | 技术选型 |
|-----|------|---------|
| **Planner Agent** | LLM 理解用户意图，分解任务为执行步骤 | LangChain4j ReAct |
| **Router** | 根据计划路由到合适的 Skill | Spring Core |
| **Step Executor** | 执行单个步骤，发布 SSE 事件 | Project Reactor |
| **Skills** | 抽象的数据处理能力（指标查询/分析/格式化） | 自研 |
| **MCP Tools** | 外部服务调用（指标API、文件等） | 自研 + HTTP Client |
| **Event Publisher** | SSE 事件发布 | Spring WebFlux |
| **SSE Controller** | 提供 `/api/chat` 流式端点 | Spring WebFlux |

---

## 3. SSE 事件流设计

### 3.1 事件类型

每一步执行过程都会推送多种事件类型，让前端清晰了解执行状态：

```
┌─────────────────────────────────────────────────────────────────────────┐
│                          SSE Event Types                                 │
├─────────────────────────────────────────────────────────────────────────┤
│                                                                          │
│  session_established  │ 连接建立                                        │
│  step_started        │ 步骤开始                                         │
│  step_completed      │ 步骤完成                                         │
│  api_request         │ API 请求发送                                     │
│  api_response        │ API 响应返回                                     │
│  planning           │ 规划步骤生成                                     │
│  thinking            │ LLM 思考中                                       │
│  error               │ 错误发生                                         │
│  task_completed      │ 任务完成                                         │
│                                                                          │
└─────────────────────────────────────────────────────────────────────────┘
```

### 3.2 事件示例

```json
// 连接建立
event: session_established
data: {"sessionId":"sess_abc123","timestamp":"2026-05-18T10:00:00Z"}

// 第1步：理解用户意图
event: step_started
data: {"step":1,"action":"understanding","message":"正在理解您的查询意图..."}

event: step_completed
data: {"step":1,"action":"understanding","result":"查询最近7天华东区销售额","confidence":0.95}

// 第2步：规划执行步骤
event: step_started
data: {"step":2,"action":"planning","message":"正在规划查询步骤..."}

event: step_completed
data: {"step":2,"action":"planning","steps":["fetch_sales","fetch_region","calculate_total"],"planId":"plan_001"}

// 第3步：调用指标API
event: step_started
data: {"step":3,"action":"fetch_sales","message":"调用销售指标API..."}

event: api_request
data: {"step":3,"tool":"MetricAPI","params":{"metric":"sales","region":"华东","days":7}}

event: api_response
data: {"step":3,"tool":"MetricAPI","status":200,"duration_ms":234,"records":156}

// 第4步：数据格式化
event: step_started
data: {"step":4,"action":"formatting","message":"正在格式化结果..."}

event: step_completed
data: {"step":4,"action":"formatting","output":"chart","format":"table"}

// 最终结果
event: task_completed
data: {"sessionId":"sess_abc123","status":"SUCCESS","totalDuration_ms":1856,"data":{"sales":123456,"unit":"CNY"}}

// 错误情况
event: error
data: {"step":3,"error":"API timeout","message":"指标服务响应超时","recoverable":true,"retryCount":1}
```

### 3.3 执行流程图

```
用户: "华东区最近7天的销售额是多少?"

┌─────────────────────────────────────────────────────────────┐
│  Step 1: Intent Understanding (LLM)                        │
│  "理解用户想查询华东区7天销售额"                               │
│                                                              │
│  SSE: step_started → understanding                          │
│  SSE: step_completed → { result, confidence }                │
└────────────────────────┬────────────────────────────────────┘
                         ▼
┌─────────────────────────────────────────────────────────────┐
│  Step 2: Planning (LLM)                                      │
│  生成执行计划:                                               │
│  1. fetch_sales (metric=sales, region=华东, days=7)        │
│  2. fetch_target (metric=target, region=华东)              │
│  3. calculate_diff                                           │
│                                                              │
│  SSE: step_started → planning                               │
│  SSE: step_completed → { steps: [...] }                    │
└────────────────────────┬────────────────────────────────────┘
                         ▼
┌─────────────────────────────────────────────────────────────┐
│  Step 3: Execute fetch_sales                                 │
│  调用 MetricAPI                                              │
│                                                              │
│  SSE: api_request → { metric, filters }                    │
│  SSE: api_response → { data, duration }                     │
└────────────────────────┬────────────────────────────────────┘
                         ▼
┌─────────────────────────────────────────────────────────────┐
│  Step 4: Execute fetch_target                               │
│  调用 MetricAPI                                              │
│                                                              │
│  SSE: api_request → { metric, filters }                    │
│  SSE: api_response → { data, duration }                     │
└────────────────────────┬────────────────────────────────────┘
                         ▼
┌─────────────────────────────────────────────────────────────┐
│  Step 5: Calculate difference                               │
│  本地计算差值                                                │
│                                                              │
│  SSE: step_completed → { result }                           │
└────────────────────────┬────────────────────────────────────┘
                         ▼
┌─────────────────────────────────────────────────────────────┐
│  task_completed                                                  │
│  返回最终数据 { sales: 123456, target: 100000, diff: +23.5% }  │
└─────────────────────────────────────────────────────────────┘
```

---

## 4. 核心数据模型

### 4.1 Intent - 用户意图

```java
public class Intent {
    String intentId;                    // 意图ID
    String rawQuery;                   // 用户原始查询
    String understoodQuery;            // LLM 理解后的查询
    List<String> entities;             // 提取的实体 (华东, 7天)
    String confidence;                // 置信度 0.0-1.0
    List<String> requiredMetrics;      // 需要查询的指标
    Map<String, Object> context;       // 额外上下文
}
```

### 4.2 Plan - 执行计划

```java
public class Plan {
    String planId;                      // 计划ID
    String intentId;                    // 关联的意图
    List<ExecutionStep> steps;          // 执行步骤列表
    Map<String, Object> variables;     // 计划变量
}

public class ExecutionStep {
    int stepOrder;                      // 步骤序号
    String stepId;                      // 步骤ID
    String action;                     // 动作名称
    String skill;                       // 使用的 Skill
    String tool;                        // 使用的 Tool
    Map<String, Object> params;        // 执行参数
    StepStatus status;                  // PENDING / RUNNING / COMPLETED / FAILED
}
```

### 4.3 SSE Event

```java
public class SSEEvent {
    String eventType;                  // 事件类型
    String sessionId;                   // 会话ID
    int step;                           // 步骤序号 (0 表示全局)
    String action;                      // 动作
    String message;                     // 展示给用户的消息
    Object data;                        // 事件数据
    OffsetDateTime timestamp;           // 时间戳
}

public enum EventType {
    SESSION_ESTABLISHED,
    STEP_STARTED,
    STEP_COMPLETED,
    API_REQUEST,
    API_RESPONSE,
    THINKING,
    ERROR,
    TASK_COMPLETED
}
```

### 4.4 Skill - 技能定义

```java
public interface Skill {
    String getName();                   // 技能名称
    String getDescription();            // 技能描述
    List<String> getCapabilities();     // 能力列表

    // 检查是否能处理该意图
    boolean canHandle(Intent intent);

    // 执行技能
    SkillResult execute(ExecutionStep step, SkillContext context);
}

public class SkillContext {
    String sessionId;
    Intent intent;
    Plan plan;
    Map<String, Object> sharedData;     // 步骤间共享数据
    Consumer<SSEEvent> eventPublisher; // 事件发布器
}
```

### 4.5 Tool - 工具定义

```java
public interface Tool {
    String getName();                   // 工具名称
    String getDescription();            // 工具描述
    String getCategory();               // 分类: api | data | format

    // 参数 Schema
    default Map<String, Object> getParametersSchema() {
        return Map.of();
    }

    // 执行工具
    ToolResult execute(Map<String, Object> params, ToolContext context);
}

public class ToolResult {
    boolean success;
    Object data;
    String error;
    long durationMs;
    Map<String, Object> metadata;
}
```

---

## 5. 组件设计

### 5.1 PlannerAgent - LLM 驱动的规划器

```java
@Service
public class PlannerAgent {

    private final ChatLanguageModel chatModel;
    private final ToolRegistry toolRegistry;

    /**
     * 理解用户意图
     */
    public Intent understand(String userQuery) {
        String prompt = PromptTemplate.of("""
            用户查询: {query}

            请理解用户的查询意图，提取:
            1. 需要查询的指标
            2. 过滤条件 (地区、时间等)
            3. 期望的结果格式

            以 JSON 格式返回。
            """);

        ChatResponse response = chatModel.generate(
            UserMessage.from(prompt.apply("query", userQuery))
        );

        return parseIntent(response.getContent());
    }

    /**
     * 生成执行计划
     */
    public Plan plan(Intent intent) {
        String prompt = PromptTemplate.of("""
            意图: {intent}
            可用工具: {tools}

            请规划执行步骤，返回 JSON 数组:
            [
              { "action": "fetch_sales", "tool": "metric_api", "params": {...} },
              { "action": "calculate", "tool": "dataframe", "params": {...} }
            ]
            """);

        // 调用 LLM 生成计划
        // 解析返回的 JSON 为 Plan 对象
    }
}
```

### 5.2 StepExecutor - 步骤执行器

```java
@Service
public class StepExecutor {

    private final ToolRegistry toolRegistry;
    private final EventPublisher eventPublisher;

    /**
     * 执行单个步骤并推送 SSE 事件
     */
    public StepResult execute(ExecutionStep step, SkillContext context) {
        // 1. 推送步骤开始
        eventPublisher.publishStepStarted(step);

        try {
            // 2. 获取 Tool 并执行
            Tool tool = toolRegistry.getTool(step.getTool());
            ToolResult result = tool.execute(step.getParams(), context.getToolContext());

            // 3. 推送步骤完成
            eventPublisher.publishStepCompleted(step, result);

            // 4. 存储共享数据供后续步骤使用
            context.getSharedData().put(step.getAction(), result.getData());

            return StepResult.success(result.getData());

        } catch (Exception e) {
            // 5. 推送失败事件
            eventPublisher.publishStepFailed(step, e);

            if (isRecoverable(e)) {
                // 可恢复错误，尝试重试
                return retry(step, context);
            }

            return StepResult.failure(e);
        }
    }
}
```

### 5.3 StreamingWorkflow - 流式工作流

```java
@Service
public class StreamingWorkflow {

    private final PlannerAgent planner;
    private final StepExecutor stepExecutor;
    private final EventPublisher eventPublisher;

    /**
     * 流式执行完整工作流
     * @return Flux<SSEEvent> - 每个步骤实时推送
     */
    public Flux<SSEEvent> executeStreamingly(String userQuery, String sessionId) {
        SkillContext context = SkillContext.create(sessionId);

        return Flux.create(sink -> {
            try {
                // === Step 1: 理解意图 ===
                eventPublisher.publishThinking(sink, "正在理解您的查询...");
                Intent intent = planner.understand(userQuery);
                context.setIntent(intent);
                sink.next(SSEEvent.step(1, "understanding", intent));

                // === Step 2: 规划步骤 ===
                eventPublisher.publishThinking(sink, "正在规划查询步骤...");
                Plan plan = planner.plan(intent);
                context.setPlan(plan);
                sink.next(SSEEvent.step(2, "planning", plan));

                // === Step 3-N: 顺序执行每个步骤 ===
                for (int i = 0; i < plan.getSteps().size(); i++) {
                    ExecutionStep step = plan.getSteps().get(i);

                    // 推送 API 请求
                    sink.next(SSEEvent.apiRequest(step, i + 3));

                    // 执行步骤
                    StepResult result = stepExecutor.execute(step, context);

                    // 推送 API 响应
                    sink.next(SSEEvent.apiResponse(step, result, i + 3));

                    if (result.isFailed() && !result.isRetryable()) {
                        throw new StepExecutionException(step, result.getError());
                    }
                }

                // === 完成 ===
                sink.next(SSEEvent.completed(sessionId, context.getSharedData()));
                sink.complete();

            } catch (Exception e) {
                sink.next(SSEEvent.error(e));
                sink.complete();
            }
        });
    }
}
```

### 5.4 MetricAPITool - 指标 API 工具

```java
@Component
@Tool(name = "metric_api", description = "查询自定义指标服务API")
public class MetricAPITool implements Tool {

    private final HttpClient httpClient;
    private final String metricsApiUrl;
    private final String apiToken;

    @Override
    public Map<String, Object> getParametersSchema() {
        return Map.of(
            "metric", Map.of("type", "string", "required", true, "description", "指标名称"),
            "filters", Map.of("type", "object", "required", false, "description", "过滤条件"),
            "timeRange", Map.of("type", "object", "required", false, "description", "时间范围")
        );
    }

    @Override
    public ToolResult execute(Map<String, Object> params, ToolContext context) {
        long startTime = System.currentTimeMillis();

        try {
            HttpResponse response = httpClient.post(metricsApiUrl + "/query")
                .header("Authorization", "Bearer " + apiToken)
                .header("Content-Type", "application/json")
                .body(Map.of(
                    "metric", params.get("metric"),
                    "filters", params.getOrDefault("filters", Map.of()),
                    "timeRange", params.getOrDefault("timeRange", Map.of())
                ))
                .timeout(30000)
                .execute();

            long duration = System.currentTimeMillis() - startTime;

            return ToolResult.builder()
                .success(response.ok())
                .data(response.getJson())
                .duration(duration)
                .metadata(Map.of("status", response.getStatus()))
                .build();

        } catch (HttpTimeoutException e) {
            return ToolResult.builder()
                .success(false)
                .error("API timeout after 30s")
                .duration(System.currentTimeMillis() - startTime)
                .metadata(Map.of("recoverable", true))
                .build();
        }
    }
}
```

### 5.5 EventPublisher - SSE 事件发布器

```java
@Service
public class EventPublisher {

    /**
     * 发布步骤开始事件
     */
    public void publishStepStarted(ExecutionStep step) {
        SSEEvent event = SSEEvent.builder()
            .eventType(STEP_STARTED)
            .step(step.getStepOrder())
            .action(step.getAction())
            .message(getStepStartMessage(step))
            .timestamp(OffsetDateTime.now())
            .build();

        publish(event);
    }

    /**
     * 发布 API 请求事件
     */
    public void publishApiRequest(ExecutionStep step, String sessionId) {
        SSEEvent event = SSEEvent.builder()
            .eventType(API_REQUEST)
            .sessionId(sessionId)
            .step(step.getStepOrder())
            .action(step.getAction())
            .message("调用 " + step.getTool())
            .data(Map.of(
                "tool", step.getTool(),
                "params", step.getParams()
            ))
            .build();

        publish(event);
    }

    /**
     * 发布 API 响应事件
     */
    public void publishApiResponse(ExecutionStep step, ToolResult result, String sessionId) {
        SSEEvent event = SSEEvent.builder()
            .eventType(API_RESPONSE)
            .sessionId(sessionId)
            .step(step.getStepOrder())
            .action(step.getAction())
            .data(Map.of(
                "tool", step.getTool(),
                "success", result.isSuccess(),
                "duration_ms", result.getDurationMs(),
                "records", getRecordCount(result.getData())
            ))
            .build();

        publish(event);
    }

    private void publish(SSEEvent event) {
        // 通过 Spring WebFlux 的 Sinks 广播到所有订阅者
        sseSink.emitNext(event, FAIL_FAST);
    }
}
```

### 5.6 SSE Controller

```java
@RestController
@RequestMapping("/api")
public class DataAgentController {

    private final StreamingWorkflow streamingWorkflow;

    /**
     * SSE 流式对话端点
     */
    @GetMapping(value = "/chat", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<SSEEvent> chat(
            @RequestParam String query,
            @RequestParam(defaultValue = "") String sessionId) {

        // 创建新会话或复用已有会话
        if (sessionId.isBlank()) {
            sessionId = UUID.randomUUID().toString();
        }

        // 返回流式响应
        return streamingWorkflow.executeStreamingly(query, sessionId)
            .startWith(SSEEvent.sessionEstablished(sessionId));
    }

    /**
     * HTTP POST 对话端点 (非流式)
     */
    @PostMapping("/chat")
    public Mono<TaskResult> chatSync(@RequestBody ChatRequest request) {
        return streamingWorkflow.execute(request.getQuery(), request.getSessionId())
            .map(this::toTaskResult);
    }
}
```

---

## 6. Skills 设计

### 6.1 MetricSkill - 指标查询技能

```java
@Service
@SkillDef(name = "metric", description = "指标数据查询技能")
public class MetricSkill implements Skill {

    private final ToolRegistry toolRegistry;

    @Override
    public boolean canHandle(Intent intent) {
        return intent.getRequiredMetrics() != null
            && !intent.getRequiredMetrics().isEmpty();
    }

    @Override
    public SkillResult execute(ExecutionStep step, SkillContext context) {
        Intent intent = context.getIntent();

        // 构建查询参数
        Map<String, Object> params = Map.of(
            "metric", intent.getRequiredMetrics().get(0),
            "filters", extractFilters(intent),
            "timeRange", extractTimeRange(intent)
        );

        // 调用 MetricAPI Tool
        Tool metricTool = toolRegistry.getTool("metric_api");
        ToolResult result = metricTool.execute(params, context.getToolContext());

        return SkillResult.builder()
            .success(result.isSuccess())
            .data(result.getData())
            .build();
    }
}
```

### 6.2 AnalyzeSkill - 数据分析技能

```java
@Service
@SkillDef(name = "analyze", description = "数据分析技能")
public class AnalyzeSkill implements Skill {

    private final ToolRegistry toolRegistry;

    @Override
    public boolean canHandle(Intent intent) {
        String query = intent.getRawQuery().toLowerCase();
        return query.contains("对比") || query.contains("增长")
            || query.contains("分析") || query.contains("趋势");
    }

    @Override
    public SkillResult execute(ExecutionStep step, SkillContext context) {
        Map<String, Object> sharedData = context.getSharedData();

        String operation = step.getParams().get("operation");
        return switch (operation) {
            case "calculate_diff" -> calculateDiff(sharedData);
            case "calculate_ratio" -> calculateRatio(sharedData);
            case "aggregate" -> aggregate(sharedData, step.getParams());
            default -> SkillResult.failure("Unknown operation: " + operation);
        };
    }
}
```

### 6.3 FormatSkill - 格式化技能

```java
@Service
@SkillDef(name = "format", description = "结果格式化技能")
public class FormatSkill implements Skill {

    @Override
    public boolean canHandle(Intent intent) {
        // 始终可以处理格式化
        return true;
    }

    @Override
    public SkillResult execute(ExecutionStep step, SkillContext context) {
        String format = step.getParams().getOrDefault("format", "table").toString();
        Object data = context.getSharedData().get("result");

        return switch (format) {
            case "table" -> formatAsTable(data);
            case "chart" -> formatAsChart(data);
            case "json" -> formatAsJson(data);
            default -> formatAsTable(data);
        };
    }
}
```

---

## 7. 目录结构设计

```
src/main/java/org/example/
├── ChatBIApplication.java              # 启动类
│
├── controller/
│   └── DataAgentController.java       # SSE 端点 /api/chat
│
├── agent/
│   ├── planner/
│   │   ├── PlannerAgent.java          # LLM 驱动的规划器
│   │   └── PromptTemplate.java        # Prompt 模板
│   └── skills/
│       ├── Skill.java                 # Skill 接口
│       ├── SkillDef.java              # Skill 定义注解
│       ├── SkillContext.java          # Skill 执行上下文
│       ├── SkillResult.java          # Skill 执行结果
│       ├── MetricSkill.java          # 指标查询技能
│       ├── AnalyzeSkill.java         # 数据分析技能
│       └── FormatSkill.java          # 格式化技能
│
├── workflow/
│   ├── StreamingWorkflow.java        # 流式工作流
│   ├── StepExecutor.java             # 步骤执行器
│   ├── ExecutionStep.java            # 执行步骤定义
│   └── Plan.java                      # 执行计划
│
├── tool/
│   ├── Tool.java                     # 工具接口
│   ├── ToolRegistry.java            # 工具注册中心
│   ├── ToolContext.java             # 工具执行上下文
│   ├── ToolResult.java              # 工具执行结果
│   └── impl/
│       ├── MetricAPITool.java       # 指标 API 工具
│       └── DataFrameTool.java       # 数据处理工具
│
├── event/
│   ├── SSEEvent.java                # SSE 事件模型
│   ├── EventType.java               # 事件类型枚举
│   └── EventPublisher.java          # 事件发布器
│
├── model/
│   ├── Intent.java                  # 用户意图
│   ├── IntentFilter.java            # 意图过滤条件
│   └── ChatRequest.java             # 聊天请求
│
├── config/
│   ├── LlmConfig.java               # LLM 配置
│   ├── ToolConfig.java              # 工具配置
│   └── WebFluxConfig.java           # WebFlux 配置
│
└── exception/
    ├── StepExecutionException.java  # 步骤执行异常
    └── ToolExecutionException.java  # 工具执行异常
```

---

## 8. 技术栈

```xml
<dependencies>
    <!-- Spring Boot 3.2+ -->
    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-webflux</artifactId>
        <description>WebFlux 支持 SSE 流式响应</description>
    </dependency>

    <!-- LangChain4j -->
    <dependency>
        <groupId>dev.langchain4j</groupId>
        <artifactId>langchain4j-openai</artifactId>
    </dependency>
    <dependency>
        <groupId>dev.langchain4j</groupId>
        <artifactId>langchain4j</artifactId>
    </dependency>

    <!-- HTTP Client -->
    <dependency>
        <groupId>io.projectreactor</groupId>
        <artifactId>reactor-core</artifactId>
    </dependency>

    <!-- Jackson -->
    <dependency>
        <groupId>com.fasterxml.jackson.datatype</groupId>
        <artifactId>jackson-datatype-jsr310</artifactId>
    </dependency>
</dependencies>
```

---

## 9. API 设计

### 9.1 SSE 流式对话

```
GET /api/chat?query={query}&sessionId={sessionId}
Accept: text/event-stream

# Response (SSE Stream)
event: session_established
data: {"sessionId":"...","timestamp":"..."}

event: step_started
data: {"step":1,"action":"understanding",...}

event: step_completed
data: {"step":1,"action":"understanding",...}

...

event: task_completed
data: {"sessionId":"...","status":"SUCCESS","data":{...}}
```

### 9.2 同步对话

```
POST /api/chat
Content-Type: application/json

{
  "query": "华东区最近7天的销售额是多少?",
  "sessionId": "sess_abc123"
}

# Response
{
  "sessionId": "sess_abc123",
  "status": "COMPLETED",
  "data": {
    "sales": 123456,
    "unit": "CNY",
    "timeRange": "最近7天",
    "region": "华东"
  },
  "duration": 1856
}
```

### 9.3 健康检查

```
GET /actuator/health

# Response
{
  "status": "UP",
  "components": {
    "llm": { "status": "UP", "model": "gpt-4" },
    "metricApi": { "status": "UP", "url": "..." }
  }
}
```

---

## 10. 错误处理

### 10.1 错误事件

```json
event: error
data: {
  "step": 3,
  "action": "fetch_sales",
  "error": "API_TIMEOUT",
  "message": "指标服务响应超时",
  "recoverable": true,
  "retryCount": 1
}
```

### 10.2 错误码

| 错误码 | 说明 | 可恢复 |
|-------|------|-------|
| API_TIMEOUT | API 超时 | 是 (重试) |
| API_ERROR | API 返回错误 | 否 |
| LLM_ERROR | LLM 调用失败 | 是 (重试) |
| INVALID_INTENT | 无法理解意图 | 否 |
| STEP_FAILED | 步骤执行失败 | 视情况 |

---

## 11. 实现计划

### Phase 1: 核心框架
- [ ] 项目结构搭建
- [ ] SSE 事件模型
- [ ] EventPublisher 实现
- [ ] 基础 Tool 接口

### Phase 2: 工作流
- [ ] StreamingWorkflow 实现
- [ ] StepExecutor 实现
- [ ] SSE Controller

### Phase 3: LLM 集成
- [ ] PlannerAgent (LLM 理解意图)
- [ ] Plan 生成
- [ ] LangChain4j 集成

### Phase 4: Skills
- [ ] MetricSkill
- [ ] AnalyzeSkill
- [ ] FormatSkill

### Phase 5: 工具实现
- [ ] MetricAPITool
- [ ] DataFrameTool

### Phase 6: 测试完善
- [ ] 单元测试
- [ ] 集成测试
- [ ] 前端 Demo

---

## 12. 参考资料

- [Spring WebFlux SSE](https://docs.spring.io/spring-framework/docs/current/reference/html/webflux.html#webflux-entity-handling)
- [LangChain4j Documentation](https://docs.langchain4j.dev/)
- [Google A2A Protocol](https://google.github.io/A2A/)
- [Claude Code Planning](https://docs.anthropic.com/)

---

*文档版本: 1.0.0*
*创建日期: 2026-05-18*