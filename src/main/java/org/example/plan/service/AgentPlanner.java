package org.example.plan.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.event.SSEEvent;
import org.example.memory.DataCache;
import org.example.plan.domain.ExecutionStep;
import org.example.plan.domain.Intent;
import org.example.plan.domain.Plan;
import org.example.plan.domain.StepResult;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Agent规划器
 * 核心规划组件，负责协调意图理解和计划执行
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AgentPlanner {

    private final IntentClassifier intentClassifier;
    private final PlanGenerator planGenerator;
    private final StepExecutor stepExecutor;
    private final DataCache dataCache;

    /**
     * 处理用户查询
     */
    public AgentResponse process(String userQuery, String sessionId) {
        log.info("Processing query for session={}: {}", sessionId, userQuery);

        if (sessionId == null || sessionId.isBlank()) {
            sessionId = UUID.randomUUID().toString();
        }

        List<SSEEvent> events = new ArrayList<>();
        Object finalResult = null;

        try {
            // Step 1: 理解意图
            events.add(SSEEvent.stepStarted(1, "understanding", "正在理解您的查询..."));
            Intent intent = intentClassifier.understand(userQuery);
            events.add(SSEEvent.stepCompleted(1, "understanding", intent));

            // Step 2: 生成计划
            events.add(SSEEvent.stepStarted(2, "planning", "正在规划查询步骤..."));
            Plan plan = planGenerator.generate(intent);
            events.add(SSEEvent.stepCompleted(2, "planning", plan));

            // Step 3: 执行计划
            events.add(SSEEvent.stepStarted(3, "executing", "开始执行查询..."));
            for (int i = 0; i < plan.getSteps().size(); i++) {
                ExecutionStep step = plan.getSteps().get(i);
                int stepNum = i + 4;

                events.add(SSEEvent.stepStarted(stepNum, step.getAction(), "执行: " + step.getDescription()));

                StepResult result = stepExecutor.execute(step, sessionId);

                if (result.isSuccess()) {
                    events.add(SSEEvent.stepCompleted(stepNum, step.getAction(), result));
                } else {
                    events.add(SSEEvent.error(stepNum, step.getAction(), result.getError()));
                    return new AgentResponse(sessionId, false, events, result.getError());
                }
            }

            // 获取最终结果
            String lastDataAlias = dataCache.getStats(sessionId).entryCount() > 0
                    ? "data_" + (System.currentTimeMillis() - 1000)
                    : null;
            finalResult = lastDataAlias != null ? dataCache.getBrief(sessionId, lastDataAlias) : null;

            events.add(SSEEvent.taskCompleted(finalResult));

            return new AgentResponse(sessionId, true, events, finalResult);

        } catch (Exception e) {
            log.error("Agent processing failed", e);
            events.add(SSEEvent.error(0, "agent", e.getMessage()));
            return new AgentResponse(sessionId, false, events, e.getMessage());
        }
    }

    /**
     * Agent响应
     */
    public record AgentResponse(
            String sessionId,
            boolean success,
            List<SSEEvent> events,
            Object result
    ) {}
}