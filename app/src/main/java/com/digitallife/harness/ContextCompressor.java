package com.digitallife.harness;

import com.digitallife.brain.LLMClient;

import java.util.ArrayList;
import java.util.List;

/**
 * 纯消息投影：超预算时从尾部保留，并在头部插入截断说明。
 * 不改 session 事件，只改模型可见历史——对齐官方 message projection seam。
 */
public final class ContextCompressor {

    public static final int BUDGET_CHARS = 6000;
    public static final int KEEP_RECENT = 6;

    public ContextCompressor() {
    }

    public List<LLMClient.ChatMessage> apply(List<LLMClient.ChatMessage> full) {
        return compact(full, BUDGET_CHARS, KEEP_RECENT);
    }

    public static List<LLMClient.ChatMessage> compact(List<LLMClient.ChatMessage> full) {
        return compact(full, BUDGET_CHARS, KEEP_RECENT);
    }

    public static List<LLMClient.ChatMessage> compact(List<LLMClient.ChatMessage> full,
                                                     int budget, int keepRecent) {
        if (full == null || full.isEmpty()) return full;
        int cap = budget > 0 ? budget : BUDGET_CHARS;
        int keep = keepRecent > 0 ? keepRecent : KEEP_RECENT;
        int total = 0;
        for (LLMClient.ChatMessage m : full) total += len(m);
        if (total <= cap) return full;

        ArrayList<LLMClient.ChatMessage> kept = new ArrayList<>();
        int used = 0;
        for (int i = full.size() - 1; i >= 0; i--) {
            LLMClient.ChatMessage m = full.get(i);
            int n = len(m);
            if (kept.size() >= keep && used + n > cap) break;
            kept.add(0, m);
            used += n;
        }
        int dropped = full.size() - kept.size();
        if (dropped > 0) {
            kept.add(0, new LLMClient.ChatMessage("user",
                    "（为控制长度，已省略更早的 " + dropped + " 条对话）"));
        }
        return kept;
    }

    static int len(LLMClient.ChatMessage m) {
        if (m == null) return 0;
        int n = m.content == null ? 0 : m.content.length();
        if (m.toolCalls != null) n += m.toolCalls.toString().length();
        return n;
    }
}
