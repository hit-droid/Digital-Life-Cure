package com.digitallife.memory;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 记忆提取结果的纯解析（v1.120.0，无 Android 依赖，可单测）。
 *
 * 期望模型输出：
 * <pre>
 * {"new_memories":[{"content":"...","category":"preference|personality|event|fact",
 *                   "weight":0.7}],
 *  "forgotten":["需要遗忘的旧记忆内容"]}
 * </pre>
 * 实际输出常被 ```json 代码块包裹、条数越界或分类写错，这里统一收敛：
 * 抽出 JSON、限制条数与长度、把 weight 夹到 [0,1]、把 category 归一到 facts 表既有取值。
 */
public final class MemoryExtractionParser {

    /** 单次最多新增记忆条数 */
    public static final int MAX_NEW = 5;
    /** 单次最多遗忘条数 */
    public static final int MAX_FORGET = 3;
    /** 单条记忆最长字符数（提示词约定 30 字，这里放宽但有上限） */
    public static final int MAX_CONTENT_CHARS = 60;

    private MemoryExtractionParser() {}

    public static final class NewMemory {
        public final String content;
        public final String category;
        public final double weight;

        public NewMemory(String content, String category, double weight) {
            this.content = content;
            this.category = category;
            this.weight = weight;
        }
    }

    public static final class Result {
        public final List<NewMemory> memories;
        public final List<String> forgotten;

        public Result(List<NewMemory> memories, List<String> forgotten) {
            this.memories = memories == null ? new ArrayList<>() : memories;
            this.forgotten = forgotten == null ? new ArrayList<>() : forgotten;
        }
    }

    /**
     * 解析模型原始输出。抽不出合法 JSON 对象时返回 {@code null}，
     * 由调用方决定是否上报错误（与"解析成功但内容为空"区分开）。
     */
    public static Result parse(String raw) {
        String json = extractJson(raw);
        if (json == null) return null;
        try {
            JSONObject obj = new JSONObject(json);
            List<NewMemory> memories = new ArrayList<>();
            JSONArray arr = obj.optJSONArray("new_memories");
            if (arr != null) {
                for (int i = 0; i < arr.length() && memories.size() < MAX_NEW; i++) {
                    JSONObject m = arr.optJSONObject(i);
                    if (m == null) continue;
                    String content = trimTo(m.optString("content", ""), MAX_CONTENT_CHARS);
                    if (content.isEmpty()) continue;
                    String category = normalizeCategory(m.optString("category", "fact"));
                    double weight = clamp(m.optDouble("weight", 0.6));
                    memories.add(new NewMemory(content, category, weight));
                }
            }
            List<String> forgotten = new ArrayList<>();
            JSONArray forget = obj.optJSONArray("forgotten");
            if (forget != null) {
                for (int i = 0; i < forget.length() && forgotten.size() < MAX_FORGET; i++) {
                    String c = forget.optString(i, "").trim();
                    if (!c.isEmpty()) forgotten.add(c);
                }
            }
            return new Result(memories, forgotten);
        } catch (Exception e) {
            return null;
        }
    }

    /** 从文本里抽出第一个完整 JSON 对象；兼容 ```json 代码块包裹。 */
    public static String extractJson(String raw) {
        if (raw == null) return null;
        int s = raw.indexOf("```json");
        if (s >= 0) {
            int e = raw.indexOf("```", s + 7);
            if (e > s) return raw.substring(s + 7, e).trim();
        }
        s = raw.indexOf('{');
        if (s < 0) return null;
        int depth = 0;
        boolean inString = false;
        boolean escaped = false;
        for (int i = s; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (inString) {
                if (escaped) escaped = false;
                else if (c == '\\') escaped = true;
                else if (c == '"') inString = false;
                continue;
            }
            if (c == '"') inString = true;
            else if (c == '{') depth++;
            else if (c == '}') {
                depth--;
                if (depth == 0) return raw.substring(s, i + 1);
            }
        }
        return null;
    }

    /**
     * 归一化分类，落到项目其他地方实际查询的取值上
     * （preference/habit/profile/event/important_date/relationship/fact）。
     */
    public static String normalizeCategory(String raw) {
        if (raw == null) return "fact";
        String c = raw.trim().toLowerCase(Locale.ROOT);
        if (c.contains("prefer") || c.contains("偏好") || c.contains("喜好")) return "preference";
        if (c.contains("person") || c.contains("profile") || c.contains("性格") || c.contains("人格")) {
            return "profile";
        }
        if (c.contains("habit") || c.contains("习惯")) return "habit";
        if (c.contains("date") || c.contains("纪念") || c.contains("日期")) return "important_date";
        if (c.contains("relation") || c.contains("关系") || c.contains("家人") || c.contains("朋友")) {
            return "relationship";
        }
        if (c.contains("event") || c.contains("事件") || c.contains("约定")) return "event";
        return "fact";
    }

    private static String trimTo(String s, int max) {
        if (s == null) return "";
        String t = s.trim();
        return t.length() <= max ? t : t.substring(0, max);
    }

    private static double clamp(double w) {
        if (w < 0) return 0;
        if (w > 1) return 1;
        return w;
    }
}
