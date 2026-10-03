package com.digitallife.ui.chat;

import com.digitallife.util.ChatStore;

import java.util.ArrayList;
import java.util.List;

/**
 * 对话历史按字符预算裁剪：超预算时从最新往回保留，至少留 {@link #DEFAULT_KEEP_RECENT} 条，
 * 并在最前面插入一条"已省略 N 条"的说明，让模型知道上下文被截断。
 *
 * <p>原先内联在 ChatActivity 中无法单测；抽出来后可以验证边界（预算内不动、
 * 恰好越界、单条超预算、note 计数正确）。
 */
public final class HistoryBudget {

    /** 上下文预算（字符数） */
    public static final int DEFAULT_BUDGET_CHARS = 6000;
    /** 至少保留的最近条数 */
    public static final int DEFAULT_KEEP_RECENT = 6;

    private HistoryBudget() {
    }

    /** 用默认预算裁剪，截断说明的时间戳取当前时间 */
    public static List<ChatStore.StoredMsg> trim(List<ChatStore.StoredMsg> full) {
        return trim(full, DEFAULT_BUDGET_CHARS, DEFAULT_KEEP_RECENT, System.currentTimeMillis());
    }

    /**
     * 按预算裁剪历史。
     *
     * @param full       完整历史（按时间正序）
     * @param budgetChars 字符预算
     * @param keepRecent 至少保留的最近条数
     * @param now        截断说明的时间戳（由调用方传入便于测试）
     * @return 原列表（预算内）或裁剪后的新列表
     */
    public static List<ChatStore.StoredMsg> trim(List<ChatStore.StoredMsg> full,
                                                 int budgetChars, int keepRecent, long now) {
        if (full == null || full.isEmpty()) return full;
        int total = 0;
        for (ChatStore.StoredMsg m : full) {
            total += m.content == null ? 0 : m.content.length();
        }
        if (total <= budgetChars) return full; // 预算内，原样返回

        // 从最新往回选
        ArrayList<ChatStore.StoredMsg> kept = new ArrayList<>();
        int used = 0;
        for (int i = full.size() - 1; i >= 0; i--) {
            ChatStore.StoredMsg m = full.get(i);
            int len = m.content == null ? 0 : m.content.length();
            if (kept.size() >= keepRecent && used + len > budgetChars) break;
            kept.add(0, m);
            used += len;
        }
        int dropped = full.size() - kept.size();
        if (dropped > 0) {
            ChatStore.StoredMsg note = new ChatStore.StoredMsg("system",
                    "（为控制长度，已省略更早的 " + dropped + " 条对话）",
                    null, null, now);
            kept.add(0, note);
        }
        return kept;
    }
}
