package com.digitallife.ui.chat;

import com.digitallife.util.ChatStore;

import org.json.JSONArray;

import java.util.List;

/**
 * 输入框为空时"猜你想问"的纯逻辑：历史指纹（决定能否复用缓存）、冷启动三条、
 * 喂给模型的上下文拼装、模型返回 JSON 的解析。
 *
 * <p>这四段原先散在 {@code ChatActivity.requestSuggestions} 里，含多处截断/边界处理，
 * 却完全无法回归。抽出来后每条规则都能单独断言。
 */
public final class SuggestionEngine {

    /** 最多返回几条建议 */
    public static final int MAX_ITEMS = 3;
    /** 单条建议最长字符数（超出截断） */
    public static final int MAX_ITEM_CHARS = 20;
    /** 拼上下文时单条历史最长字符数（超出加省略号） */
    public static final int MAX_HISTORY_CHARS = 80;

    private SuggestionEngine() {
    }

    /** 历史指纹：条数 + 末条时间戳。指纹不变即可复用上次建议 */
    public static String fingerprint(List<ChatStore.StoredMsg> hist) {
        int size = hist == null ? 0 : hist.size();
        long lastTs = (hist == null || hist.isEmpty()) ? 0 : hist.get(hist.size() - 1).timestamp;
        return size + "_" + lastTs;
    }

    /** 冷启动（无历史）时按桌宠名生成三条建议 */
    public static String[] coldStart(String petName) {
        return new String[]{
                "和" + petName + "聊聊天",
                "问问" + petName + "今天心情",
                "让" + petName + "讲个笑话"
        };
    }

    /** 拼装用于生成建议的上下文；每条历史截断到 {@link #MAX_HISTORY_CHARS} 字 */
    public static String buildPrompt(String petName, List<ChatStore.StoredMsg> hist) {
        StringBuilder context = new StringBuilder();
        context.append("你是「").append(petName).append("」。\n");
        context.append("基于以下最近的对话，为用户生成 3 条他可能想问的简短问题（每条 ≤12 字）。\n");
        context.append("要求：贴合上下文、自然、口语化。\n");
        context.append("输出 JSON 数组：[\"...\",\"...\",\"...\"]。只输出 JSON。\n\n");
        if (hist != null) {
            for (ChatStore.StoredMsg m : hist) {
                String role = m.role == null ? "user" : m.role;
                String content = m.content == null ? "" : m.content;
                if (content.length() > MAX_HISTORY_CHARS) {
                    content = content.substring(0, MAX_HISTORY_CHARS) + "…";
                }
                context.append(role).append(": ").append(content).append("\n");
            }
        }
        return context.toString();
    }

    /**
     * 解析模型返回的建议 JSON。容忍 ``` 代码块包裹；最多 {@link #MAX_ITEMS} 条，
     * 每条超过 {@link #MAX_ITEM_CHARS} 字则截断；空数组/含空项/非法 JSON 一律返回 null。
     */
    public static String[] parse(String text) {
        if (text == null) return null;
        String t = text.trim();
        // 去除 markdown 代码块包裹
        if (t.startsWith("```")) {
            int firstNewline = t.indexOf('\n');
            if (firstNewline > 0) t = t.substring(firstNewline + 1);
            int lastFence = t.lastIndexOf("```");
            if (lastFence > 0) t = t.substring(0, lastFence);
            t = t.trim();
        }
        try {
            JSONArray arr = new JSONArray(t);
            if (arr.length() == 0) return null;
            String[] out = new String[Math.min(MAX_ITEMS, arr.length())];
            for (int i = 0; i < out.length; i++) {
                String s = arr.optString(i, "").trim();
                if (s.isEmpty()) return null;
                if (s.length() > MAX_ITEM_CHARS) s = s.substring(0, MAX_ITEM_CHARS);
                out[i] = s;
            }
            return out;
        } catch (Exception e) {
            return null;
        }
    }
}
