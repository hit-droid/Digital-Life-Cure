package com.digitallife.memory;

import android.content.Context;

import com.digitallife.util.MemoryStore;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 记忆混合检索器（v1.24.0）。
 * 召回策略：
 * - 最近 20 条（时间维度）
 * - 关键词匹配 10 条（语义维度）
 * - 高重要度 5 条（weight 维度）
 * 排序：时间衰减 + 重要度加权。
 */
public class MemoryRetriever {

    private static final int RECENT_LIMIT = 20;
    private static final int KEYWORD_LIMIT = 10;
    private static final int HIGH_WEIGHT_LIMIT = 5;
    private static final double TIME_DECAY_HOURS = 24.0;  // 24h 半衰期

    private final Context ctx;
    private final MemoryStore store;

    public MemoryRetriever(Context ctx) {
        this.ctx = ctx;
        this.store = new MemoryStore(ctx);
    }

    /**
     * 综合检索：返回与查询相关的所有记忆（去重 + 排序）。
     * 如果 query 为空，则返回最近 + 高重要度。
     */
    public List<MemoryEntry> retrieve(String query) {
        List<ScoredEntry> all = new ArrayList<>();
        long now = System.currentTimeMillis();

        // 1) 时间维度：最近 20 条
        List<MemoryStore.Message> recent = store.getRecentMessages(RECENT_LIMIT);
        for (MemoryStore.Message m : recent) {
            MemoryEntry e = new MemoryEntry();
            e.id = m.timestamp;  // 用时间戳当 id
            e.content = m.content;
            e.category = m.role != null ? m.role : "msg";
            e.timestamp = m.timestamp;
            e.weight = 0.5;
            e.source = "msg";
            all.add(new ScoredEntry(e, scoreTime(now, m.timestamp) + 0.3));
        }

        // 2) 关键词维度：facts 表搜索
        if (query != null && !query.trim().isEmpty()) {
            String q = query.toLowerCase();
            List<MemoryStore.Fact> facts = store.getAllFacts();
            int matched = 0;
            for (MemoryStore.Fact f : facts) {
                if (f.content == null) continue;
                if (f.content.toLowerCase().contains(q)) {
                    MemoryEntry e = new MemoryEntry();
                    e.id = f.id;
                    e.content = f.content;
                    e.category = f.category;
                    e.timestamp = f.lastConfirmed;
                    e.weight = f.confidence;
                    e.source = "auto";
                    all.add(new ScoredEntry(e, 0.8 + f.confidence * 0.2));
                    matched++;
                    if (matched >= KEYWORD_LIMIT) break;
                }
            }
        }

        // 3) 高重要度：facts.confidence > 0.7
        List<MemoryStore.Fact> allFacts = store.getAllFacts();
        int highW = 0;
        for (MemoryStore.Fact f : allFacts) {
            if (f.confidence >= 0.7) {
                MemoryEntry e = new MemoryEntry();
                e.id = f.id;
                e.content = f.content;
                e.category = f.category;
                e.timestamp = f.lastConfirmed;
                e.weight = f.confidence;
                e.source = "auto";
                all.add(new ScoredEntry(e, f.confidence * 0.9));
                highW++;
                if (highW >= HIGH_WEIGHT_LIMIT) break;
            }
        }

        // 4) 排序 + 去重（按 content）
        // List#sort 需 API 24，改用 Collections.sort 兼容 minSdk 21
        java.util.Collections.sort(all, new Comparator<ScoredEntry>() {
            @Override
            public int compare(ScoredEntry a, ScoredEntry b) {
                return Double.compare(b.score, a.score);
            }
        });
        List<MemoryEntry> result = new ArrayList<>();
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (ScoredEntry se : all) {
            String key = se.entry.content != null ? se.entry.content : "";
            if (key.isEmpty() || seen.contains(key)) continue;
            seen.add(key);
            result.add(se.entry);
        }
        // 限制总数 25
        if (result.size() > 25) return result.subList(0, 25);
        return result;
    }

    /**
     * 渲染为可注入 LLM 的文本片段（Markdown 格式）。
     */
    public String renderForPrompt(String query) {
        List<MemoryEntry> entries = retrieve(query);
        if (entries.isEmpty()) return "（暂无相关记忆）";
        StringBuilder sb = new StringBuilder("## 你的记忆（按相关度排序）\n");
        for (MemoryEntry e : entries) {
            sb.append("- [").append(e.category).append("] ").append(e.content);
            if (e.weight >= 0.7) sb.append(" ⭐");
            sb.append("\n");
        }
        return sb.toString();
    }

    private double scoreTime(long now, long ts) {
        if (ts <= 0) return 0;
        double hoursAgo = (now - ts) / 3600_000.0;
        return Math.exp(-hoursAgo / TIME_DECAY_HOURS);
    }

    private static class ScoredEntry {
        MemoryEntry entry;
        double score;
        ScoredEntry(MemoryEntry e, double s) { entry = e; score = s; }
    }
}
