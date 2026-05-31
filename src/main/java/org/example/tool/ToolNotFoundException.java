package org.example.tool;

/**
 * 工具未找到异常
 */
public class ToolNotFoundException extends RuntimeException {
    public ToolNotFoundException(String message) {
        super(message);
    }
}