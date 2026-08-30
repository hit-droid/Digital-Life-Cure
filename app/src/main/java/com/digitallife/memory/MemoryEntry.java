package com.digitallife.memory;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * 统一记忆条目结构（v1.24.0 重构）。
 * 在原有 MemoryStore.Fact 之上提供统一抽象。
 */
public class MemoryEntry {
    public long id;             // 唯一 id（来自底层表）
    public String content;      // 记忆内容
    public String tags;         // 标签（逗号分隔）
    public String category;     // 分类
    public long timestamp;      // 创建时间
    public double weight;       // 重要度 0-1
    public String source;       // 来源：auto（自动提取）/ manual（用户手动）/ user（对话）
    public boolean pinned;      // 是否置顶

    public MemoryEntry() {}

    public MemoryEntry(String content, String tags, String category, long timestamp,
                       double weight, String source) {
        this.content = content;
        this.tags = tags;
        this.category = category;
        this.timestamp = timestamp;
        this.weight = weight;
        this.source = source;
    }

    public String formatTime() {
        return new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
                .format(new Date(timestamp));
    }

    public String shortTime() {
        long now = System.currentTimeMillis();
        long diff = now - timestamp;
        if (diff < 60_000) return "刚刚";
        if (diff < 3600_000) return (diff / 60_000) + " 分钟前";
        if (diff < 86400_000) return (diff / 3600_000) + " 小时前";
        if (diff < 7 * 86400_000L) return (diff / 86400_000) + " 天前";
        return new SimpleDateFormat("MM-dd", Locale.getDefault()).format(new Date(timestamp));
    }

    @Override
    public String toString() {
        return "[" + category + "] " + content + " (" + shortTime() + ")";
    }
}
