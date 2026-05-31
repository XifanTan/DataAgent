package org.example.controller;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.event.SSEEvent;
import org.example.plan.service.AgentPlanner;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;

import java.util.UUID;

/**
 * SSE Controller
 * 提供流式对话端点
 */
@Slf4j
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class DataAgentController {

    private final AgentPlanner agentPlanner;

    /**
     * SSE流式对话端点
     */
    @GetMapping(value = "/chat", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<SSEEvent> chat(
            @RequestParam String query,
            @RequestParam(required = false) String sessionId) {

        if (sessionId == null || sessionId.isBlank()) {
            sessionId = UUID.randomUUID().toString();
        }

        log.info("SSE chat request: query={}, sessionId={}", query, sessionId);

        // 处理请求并返回事件流
        AgentPlanner.AgentResponse response = agentPlanner.process(query, sessionId);

        // 将响应转换为SSE事件流
        return Flux.fromIterable(response.events())
                .startWith(SSEEvent.sessionEstablished(sessionId));
    }

    /**
     * 同步对话端点
     */
    @PostMapping("/chat")
    public AgentPlanner.AgentResponse chatSync(@RequestBody ChatRequest request) {
        log.info("Sync chat request: query={}, sessionId={}", request.getQuery(), request.getSessionId());

        String sessionId = request.getSessionId();
        if (sessionId == null || sessionId.isBlank()) {
            sessionId = UUID.randomUUID().toString();
        }

        return agentPlanner.process(request.getQuery(), sessionId);
    }

    /**
     * 聊天请求
     */
    public record ChatRequest(String query, String sessionId) {}
}