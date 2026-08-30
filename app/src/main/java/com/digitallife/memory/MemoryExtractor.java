package com.digitallife.memory;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import com.digitallife.brain.LLMClient;
import com.digitallife.util.MemoryStore;
import com.digitallife.util.Settings;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.List;

/**
 * 记忆自动提取器（v1.24.0）。
 * 每 6 小时（或手动触发）调用 LLM 从最近的对话中提取关键信息，
 * 写入 facts 表（自动分类、加权）。
 * 借鉴 Operit AI 的 auto-extraction 设计。
 */
public class MemoryExtractor {

    public interface Listener {
        void onExtracted(int newCount, int forgottenCount);
        void onError(String err);
    }

    private final Context ctx;
    private final MemoryStore store;
    private final Handler handler = new Handler(Looper.getMainLooper());

    public MemoryExtractor(Context ctx) {
        this.ctx = ctx;
        this.store = new MemoryStore(ctx);
    }

    /**
     * 手动触发一次提取。
     */
    public void extractNow(Listener listener) {
        Settings s = Settings.get(ctx);
        // 没有 API 配置则跳过
        if (s.getApiBase() == null || s.getApiBase().isEmpty()
                || s.getApiKey() == null || s.getApiKey().isEmpty()) {
            if (listener != null) listener.onError("未配置 API");
            return;
        }
        // 取出最近 30 条消息
        List<MemoryStore.Message> recent = store.getRecentMessages(30);
        if (recent.size() < 5) {
            if (listener != null) listener.onExtracted(0, 0);
            return;
        }
        // 构造 LLM 输入
        StringBuilder conversation = new StringBuilder();
        for (MemoryStore.Message m : recent) {
            conversation.append(m.role).append(": ").append(m.content).append("\n");
        }

        String sysPrompt = "你是「数字生命」小汐的记忆管理员。\n"
                + "从以下最近的对话中提取值得长期记住的关键信息。\n"
                + "输出 JSON 格式：\n"
                + "{\n"
                + "  \"new_memories\": [\n"
                + "    {\"content\": \"记忆内容\", \"category\": \"preference/personality/event/fact\", "
                + "\"weight\": 0.7}\n"
                + "  ],\n"
                + "  \"forgotten\": [\"需要遗忘的旧记忆内容\"]\n"
                + "}\n"
                + "规则：\n"
                + "- 偏好（如食物、习惯、兴趣）→ preference\n"
                + "- 性格特征 → personality\n"
                + "- 重要事件（纪念日、约定）→ event\n"
                + "- 事实性信息 → fact\n"
                + "- weight 0-1，重要程度越高越接近 1\n"
                + "- 每条记忆不超过 30 字\n"
                + "- 最多 5 条新记忆，0-3 条遗忘";

        LLMClient llm = new LLMClient(s.getApiBase(), s.getApiKey(), s.getModel());
        // 构造 messages：system + user
        List<LLMClient.ChatMessage> msgs = new java.util.ArrayList<>();
        msgs.add(new LLMClient.ChatMessage("system", sysPrompt));
        msgs.add(new LLMClient.ChatMessage("user", conversation.toString()));
        llm.chatStream(msgs, null, new LLMClient.StreamListener() {
            @Override
            public void onDelta(String delta) {}
            @Override
            public void onToolCall(String name, JSONObject args, String toolCallId) {}
            @Override
            public void onDone(String full) {
                handleResult(full, listener);
            }
            @Override
            public void onError(String err) {
                if (listener != null) listener.onError(err);
            }
        });
    }

    /**
     * 调度定时提取（每 6 小时）。
     */
    public void schedulePeriodic() {
        handler.removeCallbacksAndMessages(null);
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                extractNow(new Listener() {
                    @Override
                    public void onExtracted(int n, int f) {}
                    @Override
                    public void onError(String err) {}
                });
                handler.postDelayed(this, 6L * 60L * 60L * 1000L);
            }
        }, 6L * 60L * 60L * 1000L);
    }

    public void cancel() {
        handler.removeCallbacksAndMessages(null);
    }

    private void handleResult(String raw, Listener listener) {
        try {
            String json = extractJson(raw);
            if (json == null) {
                if (listener != null) listener.onError("LLM 输出非 JSON");
                return;
            }
            JSONObject obj = new JSONObject(json);
            int newCount = 0;
            JSONArray arr = obj.optJSONArray("new_memories");
            if (arr != null) {
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject m = arr.optJSONObject(i);
                    if (m == null) continue;
                    String content = m.optString("content", "").trim();
                    if (content.isEmpty()) continue;
                    String category = m.optString("category", "fact");
                    double weight = m.optDouble("weight", 0.6);
                    store.saveFact(category, content, weight, System.currentTimeMillis());
                    newCount++;
                }
            }
            int forgottenCount = 0;
            JSONArray forget = obj.optJSONArray("forgotten");
            if (forget != null) {
                for (int i = 0; i < forget.length(); i++) {
                    String content = forget.optString(i, "").trim();
                    if (content.isEmpty()) continue;
                    List<MemoryStore.Fact> facts = store.getAllFacts();
                    for (MemoryStore.Fact f : facts) {
                        if (f.content != null && f.content.contains(content)) {
                            store.deleteFact(f.id);
                            forgottenCount++;
                        }
                    }
                }
            }
            if (listener != null) listener.onExtracted(newCount, forgottenCount);
        } catch (Exception e) {
            if (listener != null) listener.onError("解析失败: " + e.getMessage());
        }
    }

    private String extractJson(String raw) {
        if (raw == null) return null;
        // 尝试提取 ```json ... ``` 块
        int s = raw.indexOf("```json");
        if (s >= 0) {
            int e = raw.indexOf("```", s + 7);
            if (e > s) return raw.substring(s + 7, e).trim();
        }
        // 尝试直接找 { ... }
        s = raw.indexOf('{');
        if (s >= 0) {
            int depth = 0;
            for (int i = s; i < raw.length(); i++) {
                char c = raw.charAt(i);
                if (c == '{') depth++;
                else if (c == '}') {
                    depth--;
                    if (depth == 0) return raw.substring(s, i + 1);
                }
            }
        }
        return null;
    }
}
