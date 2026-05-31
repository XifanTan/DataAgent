package org.example.memory;

import lombok.Builder;
import lombok.Data;

import java.util.HashMap;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 业务数据缓存
 * 用于存储步骤执行产生的业务数据（指标数据、分析结果等）
 * 用数据别名在上下文中传递引用，而非真实数据
 * 支持按需加载完整数据（供Python分析等heavy操作使用）
 */
@Data
public class BusinessData {
    private String alias;                    // 数据别名（唯一标识）
    private String dataType;                 // 数据类型：metric/analyze/result
    private Object brief;                    // 简略结果（用于显示）
    private Object fullData;                  // 完整数据（按需加载）
    private Map<String, Object> metadata;     // 元数据
    private long createdAt;                   // 创建时间
    private long size;                       // 数据大小（字节）

    @Builder
    public BusinessData(String alias, Object brief, Object fullData, Map<String, Object> metadata) {
        this.alias = alias;
        this.brief = brief;
        this.fullData = fullData != null ? fullData : brief;
        this.metadata = metadata != null ? metadata : new HashMap<>();
        this.createdAt = System.currentTimeMillis();
        this.dataType = determineDataType(brief);
        this.size = estimateSize(brief);
    }

    /**
     * 创建BusinessData
     */
    public static BusinessData create(String alias, Object data) {
        Object brief = generateBrief(data);
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("dataType", determineDataType(data));
        metadata.put("createdAt", System.currentTimeMillis());

        return BusinessData.builder()
                .alias(alias)
                .brief(brief)
                .fullData(data)
                .metadata(metadata)
                .createdAt(System.currentTimeMillis())
                .size(estimateSize(data))
                .build();
    }

    /**
     * 生成简略结果
     */
    private static Object generateBrief(Object data) {
        if (data instanceof Map) {
            Map<?, ?> map = (Map<?, ?>) data;
            Map<String, Object> brief = new HashMap<>();
            brief.put("_brief", true);
            brief.put("_count", map.size());

            // 如果是列表数据，只保留前3条
            if (map.containsKey("timeSeriesData") && map.get("timeSeriesData") instanceof java.util.List) {
                java.util.List<?> rows = (java.util.List<?>) map.get("timeSeriesData");
                brief.put("timeSeriesData", rows.stream().limit(3).collect(Collectors.toList()));
                brief.put("totalRows", rows.size());
            }
            // 保留关键汇总数据
            if (map.containsKey("currentValue")) {
                brief.put("currentValue", map.get("currentValue"));
            }
            if (map.containsKey("unit")) {
                brief.put("unit", map.get("unit"));
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
            if (map.containsKey("metric") || map.containsKey("currentValue")) {
                return "metric";
            } else if (map.containsKey("trend") || map.containsKey("change")) {
                return "analyze";
            }
        }
        return "unknown";
    }

    /**
     * 估算数据大小
     */
    private long estimateSize(Object data) {
        if (data instanceof String) {
            return ((String) data).length();
        }
        return 1024; // 默认1KB
    }
}