package org.example.event;

import lombok.Builder;
import lombok.Data;

import java.time.OffsetDateTime;
import java.util.Map;

/**
 * SSE事件
 */
@Data
@Builder
public class SSEEvent {
    private String eventType;                  // 事件类型
    private String sessionId;                   // 会话ID
    private int step;                           // 步骤序号 (0 表示全局)
    private String action;                      // 动作
    private String message;                     // 展示给用户的消息
    private Object data;                        // 事件数据
    private OffsetDateTime timestamp;           // 时间戳

    // 事件类型
    public static final String SESSION_ESTABLISHED = "session_established";
    public static final String STEP_STARTED = "step_started";
    public static final String STEP_COMPLETED = "step_completed";
    public static final String API_REQUEST = "api_request";
    public static final String API_RESPONSE = "api_response";
    public static final String THINKING = "thinking";
    public static final String ERROR = "error";
    public static final String TASK_COMPLETED = "task_completed";

    public static SSEEvent sessionEstablished(String sessionId) {
        return SSEEvent.builder()
                .eventType(SESSION_ESTABLISHED)
                .sessionId(sessionId)
                .timestamp(OffsetDateTime.now())
                .build();
    }

    public static SSEEvent stepStarted(int step, String action, String message) {
        return SSEEvent.builder()
                .eventType(STEP_STARTED)
                .step(step)
                .action(action)
                .message(message)
                .timestamp(OffsetDateTime.now())
                .build();
    }

    public static SSEEvent stepCompleted(int step, String action, Object data) {
        return SSEEvent.builder()
                .eventType(STEP_COMPLETED)
                .step(step)
                .action(action)
                .data(data)
                .timestamp(OffsetDateTime.now())
                .build();
    }

    public static SSEEvent error(int step, String action, String message) {
        return SSEEvent.builder()
                .eventType(ERROR)
                .step(step)
                .action(action)
                .message(message)
                .timestamp(OffsetDateTime.now())
                .build();
    }

    public static SSEEvent taskCompleted(Object data) {
        return SSEEvent.builder()
                .eventType(TASK_COMPLETED)
                .data(data)
                .timestamp(OffsetDateTime.now())
                .build();
    }
}