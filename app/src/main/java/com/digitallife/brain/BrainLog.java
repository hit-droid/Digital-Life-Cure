package com.digitallife.brain;

import java.util.ArrayList;
import java.util.List;

/**
 * 智能体脑日志（v1.24.0）。
 * AICore / 工具 / 计划 / ProactiveEngine 等组件输出到这里，
 * 供 AgentConsoleActivity 实时展示。
 * 借鉴 Operit AI 的 agent log ring buffer 设计。
 */
public class BrainLog {

    public static class BrainEntry {
        public final long timestamp;
        public final String tag;
        public final String message;

        public BrainEntry(long timestamp, String tag, String message) {
            this.timestamp = timestamp;
            this.tag = tag;
            this.message = message;
        }
    }

    private static final BrainLog INSTANCE = new BrainLog();
    private static final int MAX = 500;
    private final List<BrainEntry> entries = new ArrayList<>();

    public static BrainLog getInstance() {
        return INSTANCE;
    }

    public synchronized void log(String tag, String message) {
        if (message == null) return;
        // 截断过长的消息
        if (message.length() > 500) {
            message = message.substring(0, 500) + "…";
        }
        entries.add(new BrainEntry(System.currentTimeMillis(), tag, message));
        // 滚动窗口
        while (entries.size() > MAX) {
            entries.remove(0);
        }
    }

    public synchronized List<BrainEntry> recent(int n) {
        int size = entries.size();
        if (n >= size) return new ArrayList<>(entries);
        return new ArrayList<>(entries.subList(size - n, size));
    }

    public synchronized void clear() {
        entries.clear();
    }
}
