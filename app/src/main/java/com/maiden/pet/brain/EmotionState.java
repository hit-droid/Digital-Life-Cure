package com.maiden.pet.brain;

import java.util.HashMap;
import java.util.Map;

/**
 * 情绪状态系统：6 维连续情绪向量 + 关系值 + 精力值。
 * 随时间自然衰减，受互动影响。这是角色"有情绪"的内部状态。
 */
public class EmotionState {

    /** 情绪维度：开心/难过/生气/惊讶/害羞/平静 */
    public static final String[] DIMS = {"happy", "sad", "angry", "surprised", "shy", "calm"};
    private final Map<String, Float> emotion = new HashMap<>();
    private float intimacy = 0.3f;   // 亲密度 0~1
    private float energy = 0.7f;     // 精力值 0~1

    public EmotionState() {
        emotion.put("happy", 0.5f);
        emotion.put("sad", 0.0f);
        emotion.put("angry", 0.0f);
        emotion.put("surprised", 0.0f);
        emotion.put("shy", 0.1f);
        emotion.put("calm", 0.6f);
    }

    /** 每帧/每 tick 调用，情绪自然衰减 + 精力缓慢恢复 */
    public void tick(float dt) {
        for (String d : DIMS) {
            float v = emotion.get(d);
            emotion.put(d, v * (1f - dt * 0.02f));
        }
        // 精力随时间缓慢恢复
        if (energy < 1f) energy = Math.min(1f, energy + dt * 0.005f);
    }

    /** 施加一次情绪冲击 */
    public void apply(String dim, float amount) {
        Float cur = emotion.get(dim);
        if (cur == null) return;
        emotion.put(dim, Math.max(0f, Math.min(1f, cur + amount)));
    }

    /** 根据当前情绪向量推导主要情绪（用于渲染表情） */
    public String dominant() {
        String best = "neutral";
        float bestV = 0f;
        for (String d : DIMS) {
            float v = emotion.get(d);
            if (v > bestV) { bestV = v; best = d; }
        }
        return best;
    }

    /** 情绪强度 0~1 */
    public float intensity() {
        float sum = 0f;
        for (String d : DIMS) sum += emotion.get(d);
        return Math.max(0.2f, Math.min(1f, sum / 2f));
    }

    public float get(String dim) {
        Float value = emotion.get(dim);
        return value != null ? value : 0f;
    }

    public float getIntimacy() { return intimacy; }
    public void addIntimacy(float v) { intimacy = Math.max(0f, Math.min(1f, intimacy + v)); }

    public float getEnergy() { return energy; }
    public void setEnergy(float v) { energy = Math.max(0f, Math.min(1f, v)); }
    public void addEnergy(float v) { energy = Math.max(0f, Math.min(1f, energy + v)); }

    /** 序列化为可读文本，注入 System Prompt */
    public String describe() {
        StringBuilder sb = new StringBuilder();
        sb.append("当前情绪：");
        sb.append("开心").append(Math.round(get("happy") * 100)).append("%、");
        sb.append("难过").append(Math.round(get("sad") * 100)).append("%、");
        sb.append("生气").append(Math.round(get("angry") * 100)).append("%、");
        sb.append("惊讶").append(Math.round(get("surprised") * 100)).append("%、");
        sb.append("害羞").append(Math.round(get("shy") * 100)).append("%");
        sb.append("；亲密度：").append(Math.round(intimacy * 100)).append("%");
        sb.append("；精力：").append(Math.round(energy * 100)).append("%");
        return sb.toString();
    }
}
