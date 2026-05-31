# Skills 与 MCP Tools 详细设计

**文档版本**: 1.0.0
**日期**: 2026-05-31
**状态**: 设计中

---

## 1. 设计理念

### 1.1 分层架构

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                           业务流程层 (Skills)                                │
│         "用户想要做什么" - 封装业务逻辑，屏蔽技术细节                          │
└─────────────────────────────────────────────────────────────────────────────┘
                                      │
                                      ▼
┌─────────────────────────────────────────────────────────────────────────────┐
│                           工具调用层 (MCP Tools)                             │
│         "怎么做" - 底层原子操作，执行具体技术动作                              │
└─────────────────────────────────────────────────────────────────────────────┘
                                      │
                                      ▼
┌─────────────────────────────────────────────────────────────────────────────┐
│                           外部服务层 (External Services)                      │
│                    指标API、数据库、文件系统等                                │
└─────────────────────────────────────────────────────────────────────────────┘
```

### 1.2 Skill vs MCP Tool 职责划分

| 维度 | Skill (业务流程) | MCP Tool (原子操作) |
|-----|-----------------|-------------------|
| **关注点** | "做什么" | "怎么做" |
| **粒度** | 粗粒度，业务步骤 | 细粒度，原子操作 |
| **调用方** | Planner/Workflow | Skill |
| **依赖** | 调用多个MCP Tool | 调用外部服务 |
| **示例** | `MetricFetchSkill` - 如何取数 | `LogicRecognitionTool` - 识别取数逻辑 |
| | `AnalyzeSkill` - 如何分析 | `MetricValueAPI` - 获取指标值 |
| | `FormatSkill` - 如何格式化 | `FormatterTool` - 执行格式化 |

### 1.3 设计原则

1. **Skill 面向业务** - 封装完整的业务逻辑，如"查询指标"是一个Skill
2. **MCP Tool 面向技术** - 实现单一功能，如"识别取数逻辑"、"调用指标API"
3. **松耦合** - Skill 调用 Tool，但不关心 Tool 的具体实现
4. **可组合** - 多个 Skill 可以调用相同的 Tool
5. **可替换** - 更换 Tool 实现不影响 Skill 逻辑

---

## 2. Skill 设计

### 2.1 Skill 架构图

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                              «component»                                     │
│                         MetricFetchSkill                                     │
│                          (指标查询技能)                                       │
├─────────────────────────────────────────────────────────────────────────────┤
│ - toolRegistry: ToolRegistry                                                │
│ - llmGateway: LLMGateway                                                     │
│ - parser: ResponseParser                                                     │
├─────────────────────────────────────────────────────────────────────────────┤
│ + getName(): String = "metric_fetch"                                        │
│ + getDescription(): String = "封装指标查询的业务流程"                          │
│ + canHandle(intent: Intent): boolean                                        │
│ + execute(step: ExecutionStep, context: SkillContext): SkillResult         │
│ - recognizeLogic(intent: Intent): FetchLogic     «private»                 │
│ - queryMetricValue(logic: FetchLogic): Object    «private»                 │
│ - parseResult(data: Object): MetricResult         «private»                 │
└─────────────────────────────────────────────────────────────────────────────┘
                                      │
                                      │ «calls»
                                      ▼
┌────────────────────────────┐                    ┌────────────────────────────┐
│  LogicRecognitionTool      │                    │     MetricValueAPI          │
│  (识别取数逻辑)              │                    │    (获取指标值)              │
├────────────────────────────┤                    ├────────────────────────────┤
│ 输入: 用户查询              │                    │ 输入: metric, filters       │
│ 输出: FetchLogic(JSON)     │                    │ 输出: 指标值数据             │
└────────────────────────────┘                    └────────────────────────────┘
```

### 2.2 Skill 基类设计

```java
/**
 * Skill 基类接口
 * 所有 Skill 必须实现此接口
 */
public interface Skill {

    /**
     * 获取技能名称
     */
    String getName();

    /**
     * 获取技能描述
     */
    String getDescription();

    /**
     * 获取技能能力列表
     */
    List<String> getCapabilities();

    /**
     * 检查是否能处理该意图
     * @param intent 用户意图
     * @return true 如果 Skill 能处理
     */
    boolean canHandle(Intent intent);

    /**
     * 执行技能
     * @param step 执行步骤（包含具体参数）
     * @param context 技能执行上下文
     * @return 技能执行结果
     */
    SkillResult execute(ExecutionStep step, SkillContext context);
}

/**
 * Skill 执行上下文
 * 包含执行所需的所有信息
 */
@Data
public class SkillContext {
    private String sessionId;              // 会话ID
    private Intent intent;                 // 用户意图
    private Plan plan;                     // 当前计划
    private DataCache dataCache;            // 业务数据缓存（替代sharedData）
    private ToolRegistry toolRegistry;     // 工具注册中心
    private LLMGateway llmGateway;         // LLM网关
    private Consumer<SSEEvent> eventPublisher; // 事件发布器
    private Map<String, Object> attributes; // 额外属性

    /**
     * 存储数据别名（而非真实数据）
     * @param key 数据键
     * @param alias 数据别名
     */
    public void putDataAlias(String key, String alias) {
        if (attributes == null) {
            attributes = new HashMap<>();
        }
        attributes.put(key, alias);
    }

    /**
     * 获取数据别名
     * @param key 数据键
     * @return 数据别名
     */
    public String getDataAlias(String key) {
        if (attributes == null) return null;
        Object value = attributes.get(key);
        if (value instanceof String && ((String) value).startsWith("${") && ((String) value).endsWith("}")) {
            return ((String) value).substring(2, ((String) value).length() - 1);
        }
        return (String) value;
    }

    /**
     * 通过别名获取简略数据（用于显示）
     */
    public Object getDataBrief(String alias) {
        return dataCache.getBrief(sessionId, alias);
    }

    /**
     * 通过别名获取完整数据（用于分析）
     */
    public Object getDataFull(String alias) {
        return dataCache.getFull(sessionId, alias);
    }

    /**
     * 存储业务数据（自动生成别名）
     */
    public String storeData(Object data) {
        String alias = "data_" + System.currentTimeMillis();
        dataCache.store(sessionId, alias, data);
        return alias;
    }

    /**
     * 发布事件
     */
    public void publishEvent(SSEEvent event) {
        if (eventPublisher != null) {
            eventPublisher.accept(event);
        }
    }
}

/**
 * Skill 执行结果
 */
@Data
@Builder
public class SkillResult {
    private boolean success;
    private Object data;                  // 支持 BusinessData 或普通数据
    private String dataAlias;             // 数据别名（如果存储到DataCache）
    private String error;
    private String outputFormat;  // table, chart, json
    private Map<String, Object> metadata;

    public static SkillResult success(Object data) {
        return SkillResult.builder()
            .success(true)
            .data(data)
            .build();
    }

    public static SkillResult success(Object data, String dataAlias) {
        return SkillResult.builder()
            .success(true)
            .data(data)
            .dataAlias(dataAlias)
            .build();
    }

    public static SkillResult failure(String error) {
        return SkillResult.builder()
            .success(false)
            .error(error)
            .build();
    }
}

/**
 * Skill 定义注解
 * 用于自动注册 Skill
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface SkillDef {
    String name();
    String description();
    String[] capabilities() default {};
}
```

### 2.3 DataCache 集成 - 数据缓存管理

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                           DataCache                                          │
│                     (业务数据缓存)                                             │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                             │
│  ┌─────────────────────────────────────────────────────────────────────┐   │
│  │  sessionCaches: Map<String, Map<String, BusinessData>>              │   │
│  │                                                                      │   │
│  │  sessionId = "sess_123"                                             │   │
│  │  ├── "metric_sales_1" → BusinessData{alias, brief, fullData, meta}  │   │
│  │  ├── "analyze_trend_2" → BusinessData{alias, brief, fullData, meta} │   │
│  │  └── "format_result_3" → BusinessData{alias, brief, fullData, meta} │   │
│  │                                                                      │   │
│  │  sessionId = "sess_456"                                             │   │
│  │  └── ...                                                            │   │
│  └─────────────────────────────────────────────────────────────────────┘   │
│                                                                             │
│  ┌─────────────────────────────────────────────────────────────────────┐   │
│  │  BusinessData 结构:                                                  │   │
│  │  - alias: 数据别名（唯一标识）                                         │   │
│  │  - brief: 简略结果（用于显示，节省内存）                                │   │
│  │  - fullData: 完整数据（按需加载，供Python分析等）                       │   │
│  │  - metadata: 元数据（类型、来源、创建时间等）                           │   │
│  │  - size: 数据大小                                                    │   │
│  └─────────────────────────────────────────────────────────────────────┘   │
│                                                                             │
│  ┌─────────────────────────────────────────────────────────────────────┐   │
│  │  核心操作:                                                            │   │
│  │  + store(sessionId, alias, data): 将数据存入缓存                       │   │
│  │  + getBrief(sessionId, alias): 获取简略结果（用于显示）                 │   │
│  │  + getFull(sessionId, alias): 获取完整数据（用于Python分析）           │   │
│  │  + clear(sessionId): 清除会话缓存                                     │   │
│  └─────────────────────────────────────────────────────────────────────┘   │
│                                                                             │
└─────────────────────────────────────────────────────────────────────────────┘
```

### 2.4 MetricFetchSkill - 指标查询技能

```java
/**
 * MetricFetchSkill - 指标查询技能
 *
 * 封装指标查询的完整业务流程:
 * 1. 调用 LogicRecognitionTool 识别取数逻辑
 * 2. 调用 MetricValueAPI 获取指标值
 * 3. 存储结果到 DataCache（用别名引用，而非直接传递数据）
 * 4. 解析和组装结果
 *
 * Skill 调用多个 MCP Tool 协同完成业务
 */
@Service
@SkillDef(
    name = "metric_fetch",
    description = "指标数据查询技能 - 封装指标查询的完整业务流程",
    capabilities = {
        "fetch_single_metric",    // 查询单个指标
        "fetch_multi_metric",    // 查询多个指标
        "fetch_with_filter"      // 带过滤条件查询
    }
)
public class MetricFetchSkill implements Skill {

    private final ToolRegistry toolRegistry;
    private final LLMGateway llmGateway;

    @Override
    public String getName() {
        return "metric_fetch";
    }

    @Override
    public String getDescription() {
        return "指标数据查询技能 - 封装指标查询的完整业务流程";
    }

    @Override
    public List<String> getCapabilities() {
        return Arrays.asList("fetch_single_metric", "fetch_multi_metric", "fetch_with_filter");
    }

    @Override
    public boolean canHandle(Intent intent) {
        // 能处理指标查询类型的意图
        return intent.getIntentType() == IntentType.METRIC_QUERY
            || (intent.getRequiredMetrics() != null && !intent.getRequiredMetrics().isEmpty());
    }

    @Override
    public SkillResult execute(ExecutionStep step, SkillContext context) {
        Intent intent = context.getIntent();

        try {
            // === Step 1: 识别取数逻辑 ===
            // 调用 LogicRecognitionTool (MCP Tool)
            FetchLogic fetchLogic = recognizeLogic(intent, context);
            context.publishEvent(SSEEvent.builder()
                .eventType(STEP_COMPLETED)
                .action("logic_recognition")
                .data(fetchLogic)
                .build());

            // === Step 2: 获取指标值 ===
            // 调用 MetricValueAPI (MCP Tool)
            Object metricData = queryMetricValue(fetchLogic, context);

            // === Step 3: 存储到 DataCache ===
            // 用别名引用，而非直接传递数据
            String dataAlias = context.storeData(metricData);
            context.putDataAlias("metric_data", dataAlias);

            context.publishEvent(SSEEvent.builder()
                .eventType(API_RESPONSE)
                .action("metric_query")
                .data(Map.of(
                    "alias", dataAlias,
                    "brief", context.getDataBrief(dataAlias)
                ))
                .build());

            // === Step 4: 解析结果 ===
            MetricResult result = parseResult(metricData, fetchLogic);

            return SkillResult.builder()
                .success(true)
                .data(result)
                .dataAlias(dataAlias)  // 返回别名而非真实数据
                .outputFormat("table")
                .metadata(Map.of(
                    "metric", fetchLogic.getMetric(),
                    "timeRange", fetchLogic.getTimeRange(),
                    "filters", fetchLogic.getFilters()
                ))
                .build();

        } catch (Exception e) {
            return SkillResult.failure("指标查询失败: " + e.getMessage());
        }
    }

    /**
     * 识别取数逻辑
     * 调用 LogicRecognitionTool (MCP Tool)
     */
    private FetchLogic recognizeLogic(Intent intent, SkillContext context) {
        Tool recognitionTool = toolRegistry.getTool("logic_recognition");

        // 构建识别参数
        Map<String, Object> params = Map.of(
            "query", intent.getRawQuery(),
            "entities", intent.getEntities(),
            "required_metrics", intent.getRequiredMetrics()
        );

        // 调用 Tool
        ToolResult result = recognitionTool.execute(params, context.getToolContext());

        // 解析为 FetchLogic
        return parseFetchLogic(result.getData());
    }

    /**
     * 查询指标值
     * 调用 MetricValueAPI (MCP Tool)
     */
    private Object queryMetricValue(FetchLogic logic, SkillContext context) {
        Tool metricApiTool = toolRegistry.getTool("metric_value_api");

        // 构建 API 参数
        Map<String, Object> params = Map.of(
            "metric", logic.getMetric(),
            "filters", logic.getFilters(),
            "timeRange", logic.getTimeRange()
        );

        // 调用 Tool
        ToolResult result = metricApiTool.execute(params, context.getToolContext());

        if (!result.isSuccess()) {
            throw new MetricQueryException("Failed to query metric: " + result.getError());
        }

        return result.getData();
    }

    /**
     * 解析结果
     */
    private MetricResult parseResult(Object data, FetchLogic logic) {
        return MetricResult.builder()
            .metric(logic.getMetric())
            .value(extractValue(data, logic.getMetric()))
            .unit(extractUnit(data))
            .timeRange(logic.getTimeRange())
            .dimensions(extractDimensions(data))
            .build();
    }
}
```

### 2.5 AnalyzeSkill - 数据分析技能（使用数据别名）

```java
/**
 * AnalyzeSkill - 数据分析技能
 *
 * 封装数据分析的业务流程:
 * 1. 确定分析类型（对比、趋势、占比）
 * 2. 通过数据别名获取业务数据
 * 3. 调用相应工具进行分析
 * 4. 存储分析结果到 DataCache
 * 5. 汇总结果
 *
 * 重要：使用数据别名引用数据，而非直接传递完整数据
 */
@Service
@SkillDef(
    name = "analyze",
    description = "数据分析技能 - 封装数据分析的业务流程",
    capabilities = {
        "compare",      // 对比分析
        "trend",        // 趋势分析
        "proportion",   // 占比分析
        "aggregate"     // 聚合分析
    }
)
public class AnalyzeSkill implements Skill {

    private final ToolRegistry toolRegistry;
    private final DataCache dataCache;

    public enum AnalyzeType {
        COMPARISON,    // 对比分析
        TREND,         // 趋势分析
        PROPORTION,    // 占比分析
        AGGREGATE      // 聚合分析
    }

    @Override
    public String getName() {
        return "analyze";
    }

    @Override
    public String getDescription() {
        return "数据分析技能 - 封装数据分析的业务流程";
    }

    @Override
    public List<String> getCapabilities() {
        return Arrays.asList("compare", "trend", "proportion", "aggregate");
    }

    @Override
    public boolean canHandle(Intent intent) {
        String query = intent.getRawQuery().toLowerCase();
        return query.contains("对比") || query.contains("增长") || query.contains("趋势")
            || query.contains("分析") || query.contains("占比");
    }

    @Override
    public SkillResult execute(ExecutionStep step, SkillContext context) {
        String operation = step.getParams().get("operation").toString();

        // 通过数据别名获取简略数据（用于轻量级分析）
        String inputAlias = context.getDataAlias("metric_data");
        Object inputData = context.getDataBrief(inputAlias);

        // 如果是复杂分析（如Python分析），需要获取完整数据
        boolean needFullData = "python_analysis".equals(operation);
        if (needFullData && inputAlias != null) {
            inputData = context.getDataFull(inputAlias);
        }

        AnalyzeType analyzeType = determineAnalyzeType(operation);

        try {
            SkillResult result;
            switch (analyzeType) {
                case COMPARISON:
                    result = performComparison(inputData, step.getParams(), context);
                    break;
                case TREND:
                    result = performTrendAnalysis(inputData, step.getParams(), context);
                    break;
                case PROPORTION:
                    result = performProportionAnalysis(inputData, step.getParams(), context);
                    break;
                case AGGREGATE:
                    result = performAggregation(inputData, step.getParams(), context);
                    break;
                default:
                    return SkillResult.failure("Unknown analyze type: " + analyzeType);
            }

            // 如果是 Python 分析，result.data 已经是分析结果
            // 需要存储到 DataCache
            if (result.getData() != null && needFullData) {
                String resultAlias = context.storeData(result.getData());
                result.setDataAlias(resultAlias);
            }

            return result;

        } catch (Exception e) {
            return SkillResult.failure("Analysis failed: " + e.getMessage());
        }
    }

    /**
     * 对比分析
     */
    private SkillResult performComparison(Object data, Map<String, Object> params, SkillContext context) {
        // 检查是否需要对比目标数据
        String targetAlias = (String) params.get("compareTarget");
        Object compareTarget = null;
        if (targetAlias != null && context.hasDataAlias(targetAlias)) {
            // 需要完整数据才能对比
            compareTarget = context.getDataFull(targetAlias);
        }

        Tool compareTool = toolRegistry.getTool("comparison_tool");
        ToolResult toolResult = compareTool.execute(
            Map.of("data", data, "compare_with", compareTarget),
            context.getToolContext()
        );

        Object resultData = toolResult.getData();
        String resultAlias = context.storeData(resultData);

        return SkillResult.builder()
            .success(toolResult.isSuccess())
            .data(resultData)  // 轻量级结果数据
            .dataAlias(resultAlias)
            .outputFormat("table")
            .build();
    }

    /**
     * 趋势分析
     */
    private SkillResult performTrendAnalysis(Object data, Map<String, Object> params, SkillContext context) {
        String period = params.getOrDefault("period", "day").toString();
        Tool trendTool = toolRegistry.getTool("trend_tool");
        ToolResult toolResult = trendTool.execute(
            Map.of("data", data, "period", period),
            context.getToolContext()
        );

        Object resultData = toolResult.getData();
        String resultAlias = context.storeData(resultData);

        return SkillResult.builder()
            .success(toolResult.isSuccess())
            .data(resultData)
            .dataAlias(resultAlias)
            .outputFormat("chart")
            .build();
    }

    /**
     * 占比分析
     */
    private SkillResult performProportionAnalysis(Object data, Map<String, Object> params, SkillContext context) {
        String dimension = params.get("dimension").toString();
        Tool proportionTool = toolRegistry.getTool("proportion_tool");
        ToolResult toolResult = proportionTool.execute(
            Map.of("data", data, "dimension", dimension),
            context.getToolContext()
        );

        Object resultData = toolResult.getData();
        String resultAlias = context.storeData(resultData);

        return SkillResult.builder()
            .success(toolResult.isSuccess())
            .data(resultData)
            .dataAlias(resultAlias)
            .outputFormat("pie_chart")
            .build();
    }

    /**
     * 聚合分析
     */
    private SkillResult performAggregation(Object data, Map<String, Object> params, SkillContext context) {
        String aggType = params.getOrDefault("agg_type", "sum").toString();
        String groupBy = params.getOrDefault("group_by", "").toString();
        Tool aggTool = toolRegistry.getTool("aggregation_tool");
        ToolResult toolResult = aggTool.execute(
            Map.of("data", data, "agg_type", aggType, "group_by", groupBy),
            context.getToolContext()
        );

        Object resultData = toolResult.getData();
        String resultAlias = context.storeData(resultData);

        return SkillResult.builder()
            .success(toolResult.isSuccess())
            .data(resultData)
            .dataAlias(resultAlias)
            .outputFormat("table")
            .build();
    }

    private AnalyzeType determineAnalyzeType(String operation) {
        return switch (operation) {
            case "calculate_diff", "compare" -> AnalyzeType.COMPARISON;
            case "trend", "analyze_trend" -> AnalyzeType.TREND;
            case "proportion", "calculate_ratio" -> AnalyzeType.PROPORTION;
            case "aggregate", "sum", "avg" -> AnalyzeType.AGGREGATE;
            default -> AnalyzeType.AGGREGATE;
        };
    }
}
```

### 2.6 FormatSkill - 格式化技能

```java
/**
 * FormatSkill - 格式化技能
 *
 * 封装结果格式化的业务流程:
 * 1. 确定输出格式（表格、图表、JSON）
 * 2. 通过数据别名获取需要格式化的数据
 * 3. 调用 FormatterTool 执行格式化
 * 4. 返回格式化后的结果
 *
 * 重要：使用数据别名引用数据，而非直接传递完整数据
 */
@Service
@SkillDef(
    name = "format",
    description = "结果格式化技能 - 封装结果格式化的业务流程",
    capabilities = {
        "format_table",   // 表格格式
        "format_chart",   // 图表格式
        "format_json",    // JSON格式
        "format_text"     // 文本格式
    }
)
public class FormatSkill implements Skill {

    @Override
    public String getName() {
        return "format";
    }

    @Override
    public String getDescription() {
        return "结果格式化技能 - 封装结果格式化的业务流程";
    }

    @Override
    public List<String> getCapabilities() {
        return Arrays.asList("format_table", "format_chart", "format_json", "format_text");
    }

    @Override
    public boolean canHandle(Intent intent) {
        // 格式化技能始终可以处理
        return true;
    }

    @Override
    public SkillResult execute(ExecutionStep step, SkillContext context) {
        String format = step.getParams().getOrDefault("format", "table").toString();

        // 获取数据别名
        String dataAlias = context.getDataAlias("result");
        if (dataAlias == null) {
            dataAlias = context.getDataAlias("metric_data");
        }
        if (dataAlias == null) {
            dataAlias = context.getDataAlias("analyze_data");
        }

        if (dataAlias == null) {
            return SkillResult.failure("No data to format. Please provide data alias.");
        }

        // 获取简略数据进行格式化（格式化通常不需要完整数据）
        Object dataToFormat = context.getDataBrief(dataAlias);

        try {
            Object formattedResult = doFormat(dataToFormat, format);

            return SkillResult.builder()
                .success(true)
                .data(formattedResult)
                .dataAlias(dataAlias)
                .outputFormat(format)
                .metadata(Map.of(
                    "originalAlias", dataAlias,
                    "format", format
                ))
                .build();

        } catch (Exception e) {
            return SkillResult.failure("Formatting failed: " + e.getMessage());
        }
    }

    /**
     * 执行格式化
     * 调用 FormatterTool (MCP Tool)
     */
    private Object doFormat(Object data, String format) {
        Tool formatterTool = toolRegistry.getTool("formatter");

        ToolResult result = formatterTool.execute(
            Map.of("data", data, "format", format),
            null
        );

        return result.getData();
    }
}
```

### 2.7 Skill 类图 - 数据别名机制

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                              «interface»                                      │
│                               Skill                                          │
├─────────────────────────────────────────────────────────────────────────────┤
│ + getName(): String                                                          │
│ + getDescription(): String                                                   │
│ + getCapabilities(): List<String>                                            │
│ + canHandle(intent: Intent): boolean                                        │
│ + execute(step: ExecutionStep, context: SkillContext): SkillResult           │
└─────────────────────────────────────────────────────────────────────────────┘
                    △
                    │
        ┌───────────┼───────────┐
        │           │           │
        │           │           │
┌───────┴───────┐ ┌─┴───────────┴───────┐ ┌───────────────────┐
│MetricFetchSkill│ │    AnalyzeSkill     │ │    FormatSkill    │
├───────────────┤ ├──────────────────────┤ ├───────────────────┤
│- toolRegistry │ │- toolRegistry        │ │                   │
│- llmGateway   │ │- dataCache            │ │                   │
├───────────────┤ ├──────────────────────┤ ├───────────────────┤
│+ canHandle()  │ │+ canHandle()        │ │+ canHandle()      │
│+ execute()    │ │+ execute()          │ │+ execute()        │
│- recognizeLogic│ │- performComparison()│ │- doFormat()       │
│- queryMetric()│ │- performTrend()     │ │                   │
│- storeData()  │ │- getDataBrief/Full()│ │                   │
│              │ │- storeData()        │ │                   │
└───────────────┘ └──────────────────────┘ └───────────────────┘
        │                    │                    │
        │ «calls»            │ «calls»           │ «calls»
        ▼                    ▼                    ▼
┌─────────────┐        ┌─────────────┐        ┌─────────────┐
│LogicRecogTool│       │ComparisonTool│       │ Formatter   │
└─────────────┘        └─────────────┘        └─────────────┘
        │
        │ «calls»
        ▼
┌─────────────┐
│MetricValueAPI│
└─────────────┘

┌─────────────────────────────────────────────────────────────────────────────┐
│                     DataCache (业务数据缓存)                                  │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                             │
│  sessionCaches: Map<String, Map<String, BusinessData>>                      │
│                                                                             │
│  ┌─────────────────────────────────────────────────────────────────────┐   │
│  │  数据存储流程:                                                        │   │
│  │                                                                     │   │
│  │  Step 1: MetricFetchSkill.execute()                                │   │
│  │          → metricData = queryMetricValue()                         │   │
│  │          → alias = context.storeData(metricData)  // "metric_1"    │   │
│  │          → context.putDataAlias("metric_data", alias)               │   │
│  │                                                                     │   │
│  │  Step 2: AnalyzeSkill.execute()                                    │   │
│  │          → alias = context.getDataAlias("metric_data")  // "metric_1" │
│  │          → data = context.getDataBrief(alias)  // 轻量级数据       │   │
│  │          → result = performAnalysis(data)                          │   │
│  │          → resultAlias = context.storeData(result)  // "analyze_2" │   │
│  │          → context.putDataAlias("analyze_data", resultAlias)       │   │
│  │                                                                     │   │
│  │  Step 3: FormatSkill.execute()                                     │   │
│  │          → alias = context.getDataAlias("analyze_data")  // "analyze_2" │
│  │          → data = context.getDataBrief(alias)  // 用于显示          │   │
│  └─────────────────────────────────────────────────────────────────────┘   │
│                                                                             │
│  ┌─────────────────────────────────────────────────────────────────────┐   │
│  │  Python分析场景（需要完整数据）:                                        │   │
│  │                                                                     │   │
│  │  Step N: PythonAnalyzeSkill.execute()                              │   │
│  │          → alias = context.getDataAlias("metric_data")              │   │
│  │          → fullData = context.getDataFull(alias)  // 完整数据       │   │
│  │          → pythonResult = runPythonAnalysis(fullData)              │   │
│  └─────────────────────────────────────────────────────────────────────┘   │
│                                                                             │
└─────────────────────────────────────────────────────────────────────────────┘
```

---

## 3. MCP Tools 设计

### 3.1 MCP Tool 架构图

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                              MCP Tools Layer                                  │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                             │
│  ┌─────────────────┐  ┌─────────────────┐  ┌─────────────────┐            │
│  │ LogicRecognition│  │  MetricValueAPI │  │   Formatter      │            │
│  │    Tool         │  │     Tool        │  │     Tool         │            │
│  │ (识别取数逻辑)    │  │   (获取指标值)   │  │   (格式化结果)   │            │
│  ├─────────────────┤  ├─────────────────┤  ├─────────────────┤            │
│  │ 输入: query      │  │ 输入: metric,    │  │ 输入: data,      │            │
│  │     entities    │  │     filters     │  │     format      │            │
│  │ 输出: FetchLogic│  │ 输出: metric     │  │ 输出: formatted  │            │
│  │     (JSON)      │  │     value       │  │     result      │            │
│  └─────────────────┘  └─────────────────┘  └─────────────────┘            │
│                                                                             │
│  ┌─────────────────┐  ┌─────────────────┐  ┌─────────────────┐            │
│  │  ComparisonTool │  │    TrendTool    │  │ProportionTool  │            │
│  │   (对比分析)     │  │   (趋势分析)     │  │   (占比分析)     │            │
│  ├─────────────────┤  ├─────────────────┤  ├─────────────────┤            │
│  │ 输入: data,     │  │ 输入: data,     │  │ 输入: data,     │            │
│  │     compareTar │  │     period      │  │     dimension   │            │
│  │ 输出: diff      │  │ 输出: trend     │  │ 输出: proportion│            │
│  └─────────────────┘  └─────────────────┘  └─────────────────┘            │
│                                                                             │
│  ┌─────────────────┐  ┌─────────────────┐  ┌─────────────────┐            │
│  │AggregationTool │  │  LLMExtraction  │  │  ContextStorage│            │
│  │   (聚合分析)     │  │     Tool        │  │     Tool        │            │
│  ├─────────────────┤  ├─────────────────┤  ├─────────────────┤            │
│  │ 输入: data,     │  │ 输入: text,     │  │ 输入: sessionId │            │
│  │     agg_type,   │  │     schema      │  │     operation  │            │
│  │     group_by    │  │ 输出: extracted │  │ 输出: context   │            │
│  └─────────────────┘  └─────────────────┘  └─────────────────┘            │
│                                                                             │
└─────────────────────────────────────────────────────────────────────────────┘
```

### 3.2 Tool 基类设计

```java
/**
 * MCP Tool 基类接口
 * 所有 MCP Tool 必须实现此接口
 */
public interface Tool {

    /**
     * 获取工具名称
     */
    String getName();

    /**
     * 获取工具描述
     */
    String getDescription();

    /**
     * 获取工具分类
     */
    String getCategory();

    /**
     * 获取参数 Schema
     */
    default Map<String, Object> getParametersSchema() {
        return Map.of();
    }

    /**
     * 执行工具
     * @param params 执行参数
     * @param context 工具执行上下文（可为null）
     * @return 工具执行结果
     */
    ToolResult execute(Map<String, Object> params, ToolContext context);
}

/**
 * Tool 执行上下文
 */
@Data
public class ToolContext {
    private String sessionId;
    private Map<String, Object> config;
    private HttpClient httpClient;
    private String baseUrl;

    public static ToolContext of(String sessionId) {
        return new ToolContext(sessionId, Map.of());
    }
}

/**
 * Tool 执行结果
 */
@Data
@Builder
public class ToolResult {
    private boolean success;
    private Object data;
    private String error;
    private long durationMs;
    private Map<String, Object> metadata;

    public static ToolResult success(Object data) {
        return ToolResult.builder()
            .success(true)
            .data(data)
            .durationMs(System.currentTimeMillis())
            .build();
    }

    public static ToolResult success(Object data, long durationMs) {
        return ToolResult.builder()
            .success(true)
            .data(data)
            .durationMs(durationMs)
            .build();
    }

    public static ToolResult failure(String error) {
        return ToolResult.builder()
            .success(false)
            .error(error)
            .durationMs(System.currentTimeMillis())
            .build();
    }
}

/**
 * Tool 定义注解
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface ToolDef {
    String name();
    String description();
    String category() default "general";
}
```

### 3.3 LogicRecognitionTool - 识别取数逻辑

```java
/**
 * LogicRecognitionTool - 识别取数逻辑
 *
 * 这是一个 MCP Tool，负责底层技术操作：
 * - 接收用户查询和实体信息
 * - 调用 LLM 识别取数逻辑
 * - 返回结构化的 FetchLogic JSON
 *
 * Skill 不知道如何识别，只调用此 Tool 获取结果
 */
@Component
@ToolDef(
    name = "logic_recognition",
    description = "识别用户的取数逻辑，将自然语言转换为结构化的查询参数",
    category = "llm"
)
public class LogicRecognitionTool implements Tool {

    private final LLMGateway llmGateway;
    private final PromptManager promptManager;

    @Override
    public String getName() {
        return "logic_recognition";
    }

    @Override
    public String getDescription() {
        return "识别用户的取数逻辑，将自然语言转换为结构化的查询参数";
    }

    @Override
    public String getCategory() {
        return "llm";
    }

    @Override
    public Map<String, Object> getParametersSchema() {
        return Map.of(
            "query", Map.of(
                "type", "string",
                "required", true,
                "description", "用户原始查询"
            ),
            "entities", Map.of(
                "type", "object",
                "required", false,
                "description", "已提取的实体信息"
            ),
            "required_metrics", Map.of(
                "type", "array",
                "required", false,
                "description", "需要的指标列表"
            )
        );
    }

    @Override
    public ToolResult execute(Map<String, Object> params, ToolContext context) {
        long startTime = System.currentTimeMillis();

        try {
            String query = params.get("query").toString();
            @SuppressWarnings("unchecked")
            Map<String, Object> entities = (Map<String, Object>) params.getOrDefault("entities", Map.of());
            @SuppressWarnings("unchecked")
            List<String> metrics = (List<String>) params.getOrDefault("required_metrics", List.of());

            // 构建 Prompt
            Prompt prompt = promptManager.buildLogicRecognitionPrompt(query, entities, metrics);

            // 调用 LLM
            String response = llmGateway.generate(prompt);

            // 解析响应为 FetchLogic
            FetchLogic fetchLogic = parseFetchLogic(response);

            long duration = System.currentTimeMillis() - startTime;

            return ToolResult.builder()
                .success(true)
                .data(fetchLogic)
                .durationMs(duration)
                .metadata(Map.of("model", llmGateway.getDefaultModel()))
                .build();

        } catch (Exception e) {
            return ToolResult.builder()
                .success(false)
                .error("Logic recognition failed: " + e.getMessage())
                .durationMs(System.currentTimeMillis() - startTime)
                .build();
        }
    }

    /**
     * 解析 LLM 响应为 FetchLogic
     */
    private FetchLogic parseFetchLogic(String response) {
        try {
            // 假设 LLM 返回 JSON 格式的 FetchLogic
            ObjectMapper mapper = new ObjectMapper();
            JsonNode node = mapper.readTree(response);

            FetchLogic logic = FetchLogic.builder()
                .metric(node.get("metric").asText())
                .filters(parseFilters(node.get("filters")))
                .timeRange(parseTimeRange(node.get("timeRange")))
                .aggregation(node.has("aggregation") ? node.get("aggregation").asText() : "sum")
                .build();

            return logic;
        } catch (Exception e) {
            // 降级处理
            return FetchLogic.builder()
                .metric("unknown")
                .filters(Map.of())
                .timeRange(Map.of("type", "last_7_days"))
                .aggregation("sum")
                .build();
        }
    }

    private Map<String, Object> parseFilters(JsonNode filtersNode) {
        if (filtersNode == null || filtersNode.isNull()) {
            return Map.of();
        }
        return Map.of(
            "region", filtersNode.has("region") ? filtersNode.get("region").asText() : "all",
            "department", filtersNode.has("department") ? filtersNode.get("department").asText() : "all"
        );
    }

    private Map<String, Object> parseTimeRange(JsonNode timeRangeNode) {
        if (timeRangeNode == null || timeRangeNode.isNull()) {
            return Map.of("type", "last_7_days");
        }
        return Map.of(
            "type", timeRangeNode.has("type") ? timeRangeNode.get("type").asText() : "last_7_days",
            "start", timeRangeNode.has("start") ? timeRangeNode.get("start").asText() : null,
            "end", timeRangeNode.has("end") ? timeRangeNode.get("end").asText() : null
        );
    }
}

/**
 * 取数逻辑结构
 */
@Data
@Builder
public class FetchLogic {
    private String metric;                  // 指标名称
    private Map<String, Object> filters;   // 过滤条件
    private Map<String, Object> timeRange; // 时间范围
    private String aggregation;            // 聚合方式 sum/avg/count
    private Map<String, Object> dimensions; // 维度
}
```

### 3.4 MetricValueAPI - 获取指标值

```java
/**
 * MetricValueAPI - 获取指标值
 *
 * 这是一个 MCP Tool，负责底层技术操作：
 * - 接收 metric 和 filters
 * - 调用外部指标服务 API
 * - 返回指标值数据
 *
 * Skill 不知道如何调用 API，只调用此 Tool 获取数据
 */
@Component
@ToolDef(
    name = "metric_value_api",
    description = "调用指标服务API获取指标值",
    category = "api"
)
public class MetricValueAPI implements Tool {

    private final HttpClient httpClient;
    private final String metricsApiUrl;
    private final String apiToken;

    @Override
    public String getName() {
        return "metric_value_api";
    }

    @Override
    public String getDescription() {
        return "调用指标服务API获取指标值";
    }

    @Override
    public String getCategory() {
        return "api";
    }

    @Override
    public Map<String, Object> getParametersSchema() {
        return Map.of(
            "metric", Map.of(
                "type", "string",
                "required", true,
                "description", "指标名称"
            ),
            "filters", Map.of(
                "type", "object",
                "required", false,
                "description", "过滤条件 {region, department, ...}"
            ),
            "timeRange", Map.of(
                "type", "object",
                "required", false,
                "description", "时间范围 {type, start, end}"
            )
        );
    }

    @Override
    public ToolResult execute(Map<String, Object> params, ToolContext context) {
        long startTime = System.currentTimeMillis();

        try {
            String metric = params.get("metric").toString();
            @SuppressWarnings("unchecked")
            Map<String, Object> filters = (Map<String, Object>) params.getOrDefault("filters", Map.of());
            @SuppressWarnings("unchecked")
            Map<String, Object> timeRange = (Map<String, Object>) params.getOrDefault("timeRange", Map.of());

            // 构建请求体
            Map<String, Object> requestBody = Map.of(
                "metric", metric,
                "filters", filters,
                "timeRange", timeRange
            );

            // 调用指标服务 API
            HttpResponse response = httpClient.post(metricsApiUrl + "/api/v1/query")
                .header("Authorization", "Bearer " + apiToken)
                .header("Content-Type", "application/json")
                .body(requestBody)
                .timeout(30000)
                .execute();

            long duration = System.currentTimeMillis() - startTime;

            if (response.ok()) {
                return ToolResult.builder()
                    .success(true)
                    .data(response.getJson())
                    .durationMs(duration)
                    .metadata(Map.of(
                        "status", response.getStatus(),
                        "metric", metric
                    ))
                    .build();
            } else {
                return ToolResult.builder()
                    .success(false)
                    .error("API returned status: " + response.getStatus())
                    .durationMs(duration)
                    .build();
            }

        } catch (HttpTimeoutException e) {
            return ToolResult.builder()
                .success(false)
                .error("API timeout after 30s")
                .durationMs(System.currentTimeMillis() - startTime)
                .metadata(Map.of("recoverable", true))
                .build();
        } catch (Exception e) {
            return ToolResult.builder()
                .success(false)
                .error("API call failed: " + e.getMessage())
                .durationMs(System.currentTimeMillis() - startTime)
                .build();
        }
    }
}
```

### 3.5 FormatterTool - 格式化工具

```java
/**
 * FormatterTool - 格式化工具
 *
 * 这是一个 MCP Tool，负责底层技术操作：
 * - 接收数据和目标格式
 * - 执行格式化转换
 * - 返回格式化后的结果
 */
@Component
@ToolDef(
    name = "formatter",
    description = "将数据格式化为指定格式（table/chart/json/text）",
    category = "format"
)
public class FormatterTool implements Tool {

    private final ObjectMapper objectMapper;

    @Override
    public String getName() {
        return "formatter";
    }

    @Override
    public String getDescription() {
        return "将数据格式化为指定格式（table/chart/json/text）";
    }

    @Override
    public String getCategory() {
        return "format";
    }

    @Override
    public Map<String, Object> getParametersSchema() {
        return Map.of(
            "data", Map.of(
                "type", "object",
                "required", true,
                "description", "要格式化的数据"
            ),
            "format", Map.of(
                "type", "string",
                "required", true,
                "description", "目标格式: table/chart/json/text"
            ),
            "options", Map.of(
                "type", "object",
                "required", false,
                "description", "格式化选项"
            )
        );
    }

    @Override
    public ToolResult execute(Map<String, Object> params, ToolContext context) {
        long startTime = System.currentTimeMillis();

        try {
            Object data = params.get("data");
            String format = params.get("format").toString();
            @SuppressWarnings("unchecked")
            Map<String, Object> options = (Map<String, Object>) params.getOrDefault("options", Map.of());

            Object formattedResult;

            switch (format) {
                case "table":
                    formattedResult = formatAsTable(data, options);
                    break;
                case "chart":
                    formattedResult = formatAsChart(data, options);
                    break;
                case "json":
                    formattedResult = formatAsJson(data);
                    break;
                case "text":
                    formattedResult = formatAsText(data);
                    break;
                default:
                    formattedResult = formatAsTable(data, options);
            }

            return ToolResult.builder()
                .success(true)
                .data(formattedResult)
                .durationMs(System.currentTimeMillis() - startTime)
                .metadata(Map.of("format", format))
                .build();

        } catch (Exception e) {
            return ToolResult.builder()
                .success(false)
                .error("Formatting failed: " + e.getMessage())
                .durationMs(System.currentTimeMillis() - startTime)
                .build();
        }
    }

    private Map<String, Object> formatAsTable(Object data, Map<String, Object> options) {
        // 实现表格格式化逻辑
        return Map.of(
            "type", "table",
            "columns", List.of("metric", "value", "unit", "time"),
            "rows", extractRows(data)
        );
    }

    private Map<String, Object> formatAsChart(Object data, Map<String, Object> options) {
        // 实现图表格式化逻辑
        return Map.of(
            "type", "chart",
            "chartType", options.getOrDefault("chartType", "line"),
            "data", data
        );
    }

    private String formatAsJson(Object data) {
        return objectMapper.writeValueAsString(data);
    }

    private String formatAsText(Object data) {
        return objectMapper.writeValueAsString(data);
    }

    private List<List<Object>> extractRows(Object data) {
        // 从数据中提取行
        return List.of();
    }
}
```

### 3.7 Analysis Tools - 分析工具集

```java
/**
 * ComparisonTool - 对比分析工具
 */
@Component
@ToolDef(name = "comparison_tool", description = "执行对比分析", category = "analysis")
public class ComparisonTool implements Tool {

    @Override
    public String getName() { return "comparison_tool"; }

    @Override
    public String getDescription() { return "执行对比分析"; }

    @Override
    public String getCategory() { return "analysis"; }

    @Override
    public ToolResult execute(Map<String, Object> params, ToolContext context) {
        Object data = params.get("data");
        Object compareTarget = params.get("compare_with");

        // 执行对比计算
        Map<String, Object> result = calculateComparison(data, compareTarget);

        return ToolResult.success(result);
    }

    private Map<String, Object> calculateComparison(Object data, Object target) {
        // 实现对比逻辑
        return Map.of(
            "diff", 0,
            "diffPercent", 0.0,
            "trend", "up"
        );
    }
}

/**
 * TrendTool - 趋势分析工具
 */
@Component
@ToolDef(name = "trend_tool", description = "执行趋势分析", category = "analysis")
public class TrendTool implements Tool {

    @Override
    public String getName() { return "trend_tool"; }

    @Override
    public String getDescription() { return "执行趋势分析"; }

    @Override
    public String getCategory() { return "analysis"; }

    @Override
    public ToolResult execute(Map<String, Object> params, ToolContext context) {
        Object data = params.get("data");
        String period = params.getOrDefault("period", "day").toString();

        // 执行趋势分析
        Map<String, Object> result = analyzeTrend(data, period);

        return ToolResult.success(result);
    }

    private Map<String, Object> analyzeTrend(Object data, String period) {
        return Map.of(
            "trend", "up",
            "changePercent", 15.5,
            "dataPoints", List.of()
        );
    }
}

/**
 * ProportionTool - 占比分析工具
 */
@Component
@ToolDef(name = "proportion_tool", description = "执行占比分析", category = "analysis")
public class ProportionTool implements Tool {

    @Override
    public String getName() { return "proportion_tool"; }

    @Override
    public String getDescription() { return "执行占比分析"; }

    @Override
    public String getCategory() { return "analysis"; }

    @Override
    public ToolResult execute(Map<String, Object> params, ToolContext context) {
        Object data = params.get("data");
        String dimension = params.getOrDefault("dimension", "category").toString();

        // 执行占比分析
        List<Map<String, Object>> result = calculateProportion(data, dimension);

        return ToolResult.success(result);
    }

    private List<Map<String, Object>> calculateProportion(Object data, String dimension) {
        return List.of(
            Map.of("name", "A", "value", 100, "percent", 50.0),
            Map.of("name", "B", "value", 100, "percent", 50.0)
        );
    }
}

/**
 * AggregationTool - 聚合分析工具
 */
@Component
@ToolDef(name = "aggregation_tool", description = "执行聚合分析", category = "analysis")
public class AggregationTool implements Tool {

    @Override
    public String getName() { return "aggregation_tool"; }

    @Override
    public String getDescription() { return "执行聚合分析"; }

    @Override
    public String getCategory() { return "analysis"; }

    @Override
    public ToolResult execute(Map<String, Object> params, ToolContext context) {
        Object data = params.get("data");
        String aggType = params.getOrDefault("agg_type", "sum").toString();
        String groupBy = params.getOrDefault("group_by", "").toString();

        // 执行聚合分析
        Object result = aggregate(data, aggType, groupBy);

        return ToolResult.success(result);
    }

    private Object aggregate(Object data, String aggType, String groupBy) {
        // 实现聚合逻辑
        return Map.of("total", 0, "count", 0, "avg", 0.0);
    }
}

/**
 * PythonAnalysisTool - Python分析工具
 *
 * 这是一个需要完整数据的heavy工具，用于复杂的数据分析场景
 * 接收完整数据，执行Python脚本进行分析，返回结果
 */
@Component
@ToolDef(
    name = "python_analysis",
    description = "执行Python脚本进行复杂数据分析（需要完整数据）",
    category = "analysis"
)
public class PythonAnalysisTool implements Tool {

    private final ProcessBuilder processBuilder;

    @Override
    public String getName() {
        return "python_analysis";
    }

    @Override
    public String getDescription() {
        return "执行Python脚本进行复杂数据分析（需要完整数据）";
    }

    @Override
    public String getCategory() {
        return "analysis";
    }

    @Override
    public Map<String, Object> getParametersSchema() {
        return Map.of(
            "script", Map.of(
                "type", "string",
                "required", true,
                "description", "Python脚本路径或内容"
            ),
            "data", Map.of(
                "type", "object",
                "required", true,
                "description", "完整数据（从DataCache获取）"
            ),
            "params", Map.of(
                "type", "object",
                "required", false,
                "description", "传递给脚本的参数"
            )
        );
    }

    @Override
    public ToolResult execute(Map<String, Object> params, ToolContext context) {
        long startTime = System.currentTimeMillis();

        try {
            String script = params.get("script").toString();
            Object data = params.get("data");
            @SuppressWarnings("unchecked")
            Map<String, Object> scriptParams = (Map<String, Object>) params.getOrDefault("params", Map.of());

            // 将数据序列化为JSON，传递给Python脚本
            String dataJson = objectMapper.writeValueAsString(data);

            // 构建执行命令
            List<String> command = buildCommand(script, dataJson, scriptParams);

            // 执行Python脚本
            Process process = processBuilder.start();
            process.getOutputStream().write(dataJson.getBytes());
            process.getOutputStream().close();

            // 读取结果
            String result = new String(process.getInputStream().readAllBytes());
            int exitCode = process.waitFor();

            long duration = System.currentTimeMillis() - startTime;

            if (exitCode == 0) {
                // 解析Python输出
                Object analysisResult = objectMapper.readValue(result, Object.class);
                return ToolResult.builder()
                    .success(true)
                    .data(analysisResult)
                    .durationMs(duration)
                    .metadata(Map.of("exitCode", exitCode))
                    .build();
            } else {
                return ToolResult.builder()
                    .success(false)
                    .error("Python script failed with exit code: " + exitCode)
                    .durationMs(duration)
                    .metadata(Map.of("exitCode", exitCode))
                    .build();
            }

        } catch (Exception e) {
            return ToolResult.builder()
                .success(false)
                .error("Python analysis failed: " + e.getMessage())
                .durationMs(System.currentTimeMillis() - startTime)
                .build();
        }
    }

    private List<String> buildCommand(String script, String dataJson, Map<String, Object> params) {
        List<String> command = new ArrayList<>();
        command.add("python");
        command.add("-c");
        command.add(script);
        // 添加参数
        for (Map.Entry<String, Object> entry : params.entrySet()) {
            command.add("--" + entry.getKey());
            command.add(entry.getValue().toString());
        }
        return command;
    }
}
```

### 3.8 Tool 类图 - 数据别名机制

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                              «interface»                                      │
│                                Tool                                           │
├─────────────────────────────────────────────────────────────────────────────┤
│ + getName(): String                                                          │
│ + getDescription(): String                                                   │
│ + getCategory(): String                                                      │
│ + getParametersSchema(): Map<String, Object>                                │
│ + execute(params: Map, context: ToolContext): ToolResult                     │
└─────────────────────────────────────────────────────────────────────────────┘
                    △
                    │
        ┌───────────┼───────────┬─────────────┐
        │           │           │             │
        │           │           │             │
┌───────┴───────┐ ┌─┴──────────┴──┐ ┌─────────┴─────────┐ ┌─────────────────┐
│LogicRecognition│ │ MetricValueAPI │ │    Formatter      │ │  ComparisonTool │
│    Tool        │ │     Tool       │ │      Tool         │ │                 │
├────────────────┤ ├────────────────┤ ├───────────────────┤ ├─────────────────┤
│- llmGateway    │ │- httpClient    │ │- objectMapper     │ │                 │
│- promptManager│ │- metricsApiUrl │ │                   │ │                 │
├────────────────┤ ├────────────────┤ ├───────────────────┤ ├─────────────────┤
│+ execute()    │ │+ execute()    │ │+ execute()        │ │+ execute()      │
│- parseFetchLogic│ │               │ │- formatAsTable()  │ │- calculateComp()│
│                │ │               │ │- formatAsChart()  │ │                 │
└────────────────┘ └───────────────┘ └───────────────────┘ └─────────────────┘

┌─────────────────┐ ┌─────────────────┐ ┌─────────────────┐ ┌─────────────────┐
│    TrendTool    │ │ ProportionTool  │ │AggregationTool  │ │ PythonAnalysis  │
├─────────────────┤ ├─────────────────┤ ├─────────────────┤ ├─────────────────┤
│                 │ │                 │ │                 │ │- processBuilder │
├─────────────────┤ ├─────────────────┤ ├─────────────────┤ ├─────────────────┤
│+ execute()      │ │+ execute()      │ │+ execute()      │ │+ execute()      │
│- analyzeTrend() │ │- calculateProp()│ │- aggregate()    │ │- runPython()    │
│                 │ │                 │ │                 │ │  (需要完整数据)  │
└─────────────────┘ └─────────────────┘ └─────────────────┘ └─────────────────┘
```

---

## 4. Skill 与 Tool 交互流程

### 4.1 MetricFetchSkill 执行流程

```
┌─────────────┐    ┌─────────────────┐    ┌─────────────────────┐    ┌────────────────┐
│MetricFetch  │    │LogicRecognition │    │    MetricValueAPI    │    │   External     │
│   Skill     │    │      Tool       │    │        Tool          │    │  Metrics API   │
└──────┬──────┘    └────────┬────────┘    └──────────┬──────────┘    └───────┬────────┘
       │                    │                         │                      │
       │ execute(step, ctx)│                         │                      │
       │───────────────────►│                         │                      │
       │                    │                         │                      │
       │                    │ execute(params)        │                      │
       │                    │────────────────────────►│                      │
       │                    │                         │                      │
       │                    │  FetchLogic (JSON)    │                      │
       │                    │◄────────────────────────│                      │
       │                    │                         │                      │
       │  FetchLogic        │                         │                      │
       │◄────────────────────│                         │                      │
       │                    │                         │                      │
       │                    │                         │ execute(metric,filters)
       │                    │                         │─────────────────────►│
       │                    │                         │                      │
       │                    │                         │    Metric Value      │
       │                    │                         │◄─────────────────────│
       │                    │                         │                      │
       │  metricData        │                         │                      │
       │◄──────────────────────────────────────────────│                      │
       │                    │                         │                      │
       │ parseResult()      │                         │                      │
       │                    │                         │                      │
       │ SkillResult        │                         │                      │
       │────────────────────│                         │                      │
       │                    │                         │                      │
```

### 4.2 完整流程时序图

```
┌──────────┐   ┌───────────────┐   ┌─────────────────┐   ┌──────────────────┐   ┌─────────────┐
│   User   │   │ StreamingWork │   │ MetricFetchSkill│   │LogicRecognition  │   │MetricValueAPI│
└────┬─────┘   └───────┬───────┘   └────────┬────────┘   └────────┬─────────┘   └──────┬──────┘
     │                  │                   │                    │                    │
     │ "华东7天销售额"   │                   │                    │                    │
     │─────────────────►│                   │                    │                    │
     │                  │                   │                    │                    │
     │                  │ executeStreamingly│                    │                    │
     │                  │──────────────────►│                    │                    │
     │                  │                   │                    │                    │
     │                  │   step_started(understanding)           │                    │
     │                  │◄──────────────────│                    │                    │
     │                  │                   │                    │                    │
     │                  │   step_started(fetch_metric)           │                    │
     │                  │                   │                    │                    │
     │                  │                   │ recognizeLogic()   │                    │
     │                  │                   │───────────────────►│                    │
     │                  │                   │                    │                    │
     │                  │                   │     FetchLogic     │                    │
     │                  │                   │◄────────────────────│                    │
     │                  │                   │                    │                    │
     │                  │                   │ queryMetricValue()                    │
     │                  │                   │─────────────────────────────────────────►│
     │                  │                   │                    │                    │
     │                  │                   │                    │    metric_data    │
     │                  │                   │◄──────────────────────────────────────────│
     │                  │                   │                    │                    │
     │                  │   step_completed  │                    │                    │
     │                  │◄─────────────────│                    │                    │
     │                  │                   │                    │                    │
     │  SSE Event       │                   │                    │                    │
     │◄─────────────────│                   │                    │                    │
     │                  │                   │                    │                    │
```

---

## 5. ToolRegistry - 工具注册中心

### 5.1 工具注册中心设计

```java
/**
 * 工具注册中心
 * 负责管理和访问所有 MCP Tools
 */
@Service
public class ToolRegistry {

    private final Map<String, Tool> tools = new ConcurrentHashMap<>();

    /**
     * 注册工具
     */
    public void register(Tool tool) {
        tools.put(tool.getName(), tool);
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
        return new ArrayList<>(tools.values());
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
     * 获取工具描述（用于 LLM 规划）
     */
    public String getToolsDescription() {
        return tools.values().stream()
            .map(t -> String.format("- %s: %s", t.getName(), t.getDescription()))
            .collect(Collectors.joining("\n"));
    }
}

/**
 * 工具未找到异常
 */
public class ToolNotFoundException extends RuntimeException {
    public ToolNotFoundException(String message) {
        super(message);
    }
}
```

### 5.2 SkillRegistry - 技能注册中心

```java
/**
 * 技能注册中心
 * 负责管理和访问所有 Skills
 */
@Service
public class SkillRegistry {

    private final Map<String, Skill> skills = new ConcurrentHashMap<>();

    /**
     * 注册技能（通过 @SkillDef 注解自动注册）
     */
    @PostConstruct
    public void init() {
        // 通过 Spring 扫描所有 @SkillDef 注解的 Bean
        // 并注册到 registry
    }

    /**
     * 注册技能
     */
    public void register(Skill skill) {
        skills.put(skill.getName(), skill);
    }

    /**
     * 获取技能
     */
    public Skill getSkill(String name) {
        Skill skill = skills.get(name);
        if (skill == null) {
            throw new SkillNotFoundException("Skill not found: " + name);
        }
        return skill;
    }

    /**
     * 查找能处理意图的技能
     */
    public Optional<Skill> findSkillFor(Intent intent) {
        return skills.values().stream()
            .filter(s -> s.canHandle(intent))
            .findFirst();
    }

    /**
     * 获取所有技能
     */
    public List<Skill> getAllSkills() {
        return new ArrayList<>(skills.values());
    }
}

/**
 * 技能未找到异常
 */
public class SkillNotFoundException extends RuntimeException {
    public SkillNotFoundException(String message) {
        super(message);
    }
}
```

---

## 5. 数据别名传递机制

### 5.1 数据流程图

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                        数据别名传递流程                                        │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                             │
│  用户: "华东区最近7天的销售额是多少?"                                          │
│                                        │                                    │
│                                        ▼                                    │
│  ┌─────────────────────────────────────────────────────────────────────┐   │
│  │  Step 1: MetricFetchSkill.execute()                                  │   │
│  │                                                                      │   │
│  │  1.1 queryMetricValue() → metricData (完整指标数据)                 │   │
│  │  1.2 alias = context.storeData(metricData) → "metric_1"             │   │
│  │  1.3 context.putDataAlias("metric_data", alias)                     │   │
│  │  1.4 return SkillResult(data=简略结果, dataAlias="metric_1")        │   │
│  │                                                                      │   │
│  │  DataCache 存储:                                                      │   │
│  │  sessionId → "metric_1" → BusinessData{                              │   │
│  │    alias: "metric_1",                                                 │   │
│  │    brief: {rows: [...3条], totalRows: 156, _brief: true},            │   │
│  │    fullData: {rows: [...156条], ...} // 完整数据                      │   │
│  │  }                                                                   │   │
│  └─────────────────────────────────────────────────────────────────────┘   │
│                                        │                                    │
│                                        ▼                                    │
│  ┌─────────────────────────────────────────────────────────────────────┐   │
│  │  Step 2: AnalyzeSkill.execute() (趋势分析)                          │   │
│  │                                                                      │   │
│  │  2.1 alias = context.getDataAlias("metric_data") → "metric_1"      │   │
│  │  2.2 data = context.getDataBrief(alias) → {rows:[...3条], total}   │   │
│  │      (轻量级数据，用于简单分析)                                        │   │
│  │  2.3 result = performTrendAnalysis(data) → trendResult              │   │
│  │  2.4 resultAlias = context.storeData(trendResult) → "trend_2"     │   │
│  │  2.5 context.putDataAlias("analyze_data", resultAlias)             │   │
│  │  2.6 return SkillResult(data=trendResult, dataAlias="trend_2")    │   │
│  └─────────────────────────────────────────────────────────────────────┘   │
│                                        │                                    │
│                                        ▼                                    │
│  ┌─────────────────────────────────────────────────────────────────────┐   │
│  │  Step 3: FormatSkill.execute()                                       │   │
│  │                                                                      │   │
│  │  3.1 alias = context.getDataAlias("analyze_data") → "trend_2"       │   │
│  │  3.2 data = context.getDataBrief(alias) → 格式化后的图表数据         │   │
│  │  3.3 return SkillResult(data=formattedData, dataAlias="trend_2")   │   │
│  └─────────────────────────────────────────────────────────────────────┘   │
│                                        │                                    │
│                                        ▼                                    │
│  最终输出给用户: SSE事件推送简略结果 + dataAlias                            │
│                                                                             │
└─────────────────────────────────────────────────────────────────────────────┘
```

### 5.2 Python分析场景（需要完整数据）

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                     Python分析 - 获取完整数据                                 │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                             │
│  ┌─────────────────────────────────────────────────────────────────────┐   │
│  │  PythonAnalysisTool.execute()                                        │   │
│  │                                                                      │   │
│  │  1. alias = context.getDataAlias("metric_data") → "metric_1"        │   │
│  │                                                                      │   │
│  │  2. fullData = context.getDataFull(alias)                           │   │
│  │     → {rows: [...156条完整数据], ...}  // 加载完整数据到内存          │   │
│  │                                                                      │   │
│  │  3. 将完整数据序列化为JSON，传递给Python脚本                          │   │
│  │                                                                      │   │
│  │  4. pythonResult = runPythonScript(script, dataJson)               │   │
│  │                                                                      │   │
│  │  5. resultAlias = context.storeData(pythonResult)                  │   │
│  │                                                                      │   │
│  │  6. return ToolResult(data=pythonResult)                           │   │
│  └─────────────────────────────────────────────────────────────────────┘   │
│                                                                             │
│  适用场景:                                                                  │
│  - 复杂统计建模                                                             │
│  - 机器学习预测                                                             │
│  - 自定义Python分析脚本                                                     │
│                                                                             │
└─────────────────────────────────────────────────────────────────────────────┘
```

### 5.3 数据生命周期

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                        数据生命周期管理                                        │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                             │
│  创建 ──► 使用 ──► 释放                                                     │
│    │        │        │                                                    │
│    ▼        ▼        ▼                                                    │
│  store()  getBrief() clear()                                               │
│            getFull()  (会话结束)                                            │
│              │                                                            │
│              ▼                                                            │
│         按需加载完整数据                                                    │
│         (仅Python分析等heavy操作)                                           │
│                                                                             │
│  内存管理策略:                                                               │
│  - 简略数据（brief）: 始终在缓存中，用于显示                                 │
│  - 完整数据（fullData）: 仅在需要时加载，使用后可不保留                        │
│  - 会话结束时: 清除整个会话的缓存                                            │
│                                                                             │
└─────────────────────────────────────────────────────────────────────────────┘
```

---

## 6. 目录结构

```
src/main/java/org/example/
├── skill/
│   ├── Skill.java                    # Skill 接口
│   ├── SkillDef.java                 # Skill 注解
│   ├── SkillContext.java            # Skill 上下文（支持数据别名）
│   ├── SkillResult.java             # Skill 结果
│   ├── SkillRegistry.java           # 技能注册中心
│   ├── MetricFetchSkill.java        # 指标查询技能
│   ├── AnalyzeSkill.java            # 数据分析技能
│   └── FormatSkill.java             # 格式化技能
│
├── tool/
│   ├── Tool.java                     # Tool 接口
│   ├── ToolDef.java                  # Tool 注解
│   ├── ToolContext.java             # Tool 上下文
│   ├── ToolResult.java              # Tool 结果
│   ├── ToolRegistry.java            # 工具注册中心
│   └── impl/
│       ├── LogicRecognitionTool.java    # 识别取数逻辑
│       ├── MetricValueAPI.java         # 获取指标值
│       ├── FormatterTool.java          # 格式化工具
│       ├── ComparisonTool.java         # 对比分析工具
│       ├── TrendTool.java              # 趋势分析工具
│       ├── ProportionTool.java         # 占比分析工具
│       ├── AggregationTool.java        # 聚合分析工具
│       └── PythonAnalysisTool.java      # Python分析工具（需要完整数据）
│
├── cache/
│   ├── DataCache.java               # 业务数据缓存（核心）
│   └── BusinessData.java           # 业务数据封装（别名/简略/完整）
│
├── model/
│   ├── FetchLogic.java               # 取数逻辑模型
│   ├── MetricResult.java             # 指标结果模型
│   └── DataAlias.java                # 数据别名模型
```

---

## 7. 实现清单

### DataCache 实现
- [ ] DataCache 业务数据缓存
- [ ] BusinessData 业务数据封装
- [ ] CacheStats 缓存统计

### MCP Tools 实现
- [ ] Tool 接口和基类
- [ ] ToolRegistry 工具注册中心
- [ ] LogicRecognitionTool
- [ ] MetricValueAPI
- [ ] FormatterTool
- [ ] ComparisonTool
- [ ] TrendTool
- [ ] ProportionTool
- [ ] AggregationTool
- [ ] PythonAnalysisTool（需要完整数据）

### Skills 实现
- [ ] Skill 接口和基类（支持数据别名）
- [ ] SkillContext（支持数据别名）
- [ ] SkillRegistry 技能注册中心
- [ ] MetricFetchSkill
- [ ] AnalyzeSkill（使用数据别名）
- [ ] FormatSkill

### 集成测试
- [ ] Skill 与 Tool 集成测试
- [ ] 数据别名传递测试
- [ ] 完整数据加载测试（Python分析场景）
- [ ] 端到端流程测试

---

*文档版本: 1.1.0*
*更新日期: 2026-05-31*
*更新内容: 新增 DataCache 数据缓存机制，支持数据别名传递*