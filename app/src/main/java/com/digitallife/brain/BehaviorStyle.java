package com.digitallife.brain;

/**
 * BehaviorStyle：AI 行为风格包。
 * 由 LLM 输出的结构化 JSON 解析而来，驱动本地物理引擎的参数细节。
 * 这是「AI 实时控制人偶」的核心数据载体——
 * AI 的每个决策都体现为一段时间的姿态、动作幅度、视线模式等。
 */
public class BehaviorStyle {

    /** 视线模式 */
    public static final int GAZE_WANDER = 0;   // 四处漫游
    public static final int GAZE_FOLLOW = 1;   // 注视用户
    public static final int GAZE_AVERT = 2;    // 避开视线
    public static final int GAZE_FIXED = 3;    // 定住前方

    /** 精力 0~1：驱动呼吸深度/速度、身体晃动、眨眼频率 */
    public float energy = 0.7f;

    /** 警觉度 0~1：驱动眨眼频率、头部抬起、视线活跃度 */
    public float alertness = 0.6f;

    /** 社交倾向 0~1：驱动主动说话概率 */
    public float sociability = 0.5f;

    /** 动作幅度 0~1：驱动身体晃动幅度 */
    public float amplitude = 0.5f;

    /** 动作速度 0~1：驱动呼吸/晃动速度 */
    public float speed = 0.8f;

    /** 视线模式 */
    public int gazeMode = GAZE_WANDER;

    /** 表情（F01~F08） */
    public String expression = "F01";

    /** 姿态标签（仅用于 prompt 理解，物理参数由上面数值驱动） */
    public String posture = "relaxed";

    /** 从 LLM JSON 解析；解析失败返回 null */
    public static BehaviorStyle fromJson(org.json.JSONObject o) {
        if (o == null) return null;
        BehaviorStyle s = new BehaviorStyle();
        org.json.JSONObject mood = o.optJSONObject("mood");
        if (mood != null) {
            s.energy = clamp(mood.optDouble("energy", s.energy));
            s.alertness = clamp(mood.optDouble("alertness", s.alertness));
            s.sociability = clamp(mood.optDouble("sociability", s.sociability));
        }
        org.json.JSONObject b = o.optJSONObject("behavior");
        if (b != null) {
            s.amplitude = clamp(b.optDouble("amplitude", s.amplitude));
            s.speed = clamp(b.optDouble("speed", s.speed));
            String gaze = b.optString("gaze", "");
            switch (gaze) {
                case "wander": s.gazeMode = GAZE_WANDER; break;
                case "follow": s.gazeMode = GAZE_FOLLOW; break;
                case "avert":  s.gazeMode = GAZE_AVERT;  break;
                case "fixed":  s.gazeMode = GAZE_FIXED;  break;
                default: s.gazeMode = GAZE_WANDER; break;
            }
            s.posture = b.optString("posture", s.posture);
        }
        String expr = o.optString("expression", "");
        if (!expr.isEmpty()) s.expression = expr;
        return s;
    }

    private static float clamp(double v) {
        return (float) Math.max(0.0, Math.min(1.0, v));
    }

    /** v1.26.0：序列化为 SharedPreferences 键值 */
    public java.util.Map<String, Object> snapshot() {
        java.util.Map<String, Object> map = new java.util.HashMap<>();
        map.put("bs_energy", energy);
        map.put("bs_alertness", alertness);
        map.put("bs_sociability", sociability);
        map.put("bs_amplitude", amplitude);
        map.put("bs_speed", speed);
        map.put("bs_gazeMode", gazeMode);
        map.put("bs_expression", expression);
        map.put("bs_posture", posture);
        return map;
    }

    /** v1.26.0：从 SharedPreferences 恢复（缺失字段保持当前值） */
    public void restore(java.util.Map<String, Object> map) {
        if (map == null) return;
        Object e = map.get("bs_energy"); if (e instanceof Number) energy = ((Number) e).floatValue();
        Object al = map.get("bs_alertness"); if (al instanceof Number) alertness = ((Number) al).floatValue();
        Object so = map.get("bs_sociability"); if (so instanceof Number) sociability = ((Number) so).floatValue();
        Object am = map.get("bs_amplitude"); if (am instanceof Number) amplitude = ((Number) am).floatValue();
        Object sp = map.get("bs_speed"); if (sp instanceof Number) speed = ((Number) sp).floatValue();
        Object gm = map.get("bs_gazeMode"); if (gm instanceof Number) gazeMode = ((Number) gm).intValue();
        Object ex = map.get("bs_expression"); if (ex instanceof String) expression = (String) ex;
        Object po = map.get("bs_posture"); if (po instanceof String) posture = (String) po;
    }

    /** 行为包应包含的字段描述（注入 prompt 用） */
    public static String describeFormat() {
        return "{\n"
            + "  \"reply\": \"要回复用户的文本，若无实质内容则为空字符串\",\n"
            + "  \"expression\": \"表情ID，F01~F08（F01平静 F02开心 F03难过 F04生气 F05惊讶 F06害羞）\",\n"
            + "  \"mood\": { \"energy\": 0.0~1.0, \"alertness\": 0.0~1.0, \"sociability\": 0.0~1.0 },\n"
            + "  \"behavior\": { \"amplitude\": 0.0~1.0, \"speed\": 0.0~1.0, \"gaze\": \"wander|follow|avert|fixed\", \"posture\": \"relaxed|curious|tired|alert|shy\" }\n"
            + "}";
    }
}
