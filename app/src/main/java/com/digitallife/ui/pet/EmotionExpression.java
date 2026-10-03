package com.digitallife.ui.pet;

import com.digitallife.brain.EmotionState;

/**
 * 情绪外显（Issue #48，纯逻辑，JVM 可测）。
 *
 * <p>把 {@link EmotionState} 的连续情绪向量映射为待机表情（{@code F01}~{@code F06}），
 * 并带两层防抖，避免边界抖动导致表情每秒乱跳：</p>
 * <ul>
 *   <li><b>最短停留</b>：切换后 {@link #MIN_DWELL_MS} 内不再切，给用户可感知的时间；</li>
 *   <li><b>迟滞</b>：切到某个非平静表情前，该情绪必须明显领先「当前表情对应情绪」
 *       {@link #SWITCH_MARGIN}，防止两个相近情绪来回抢主导。</li>
 * </ul>
 *
 * <p>平静（{@code F01}）是兜底：主要情绪低于 {@link #CALM_THRESHOLD}、或平静值本身占优时回落，
 * 保证「没有明显情绪」时不硬凹表情。</p>
 *
 * <p>不碰 Android API，也不改 {@code brain/} 内部算法——只读 {@link EmotionState} 的公开 getter。</p>
 */
public final class EmotionExpression {

    /** 换表情的最短停留：4 秒内不再切换 */
    public static final long MIN_DWELL_MS = 4000L;

    /** 迟滞阈值：目标情绪需比当前表情对应情绪高出这个量才切换 */
    public static final float SWITCH_MARGIN = 0.12f;

    /** 主要情绪低于此值视为平静，回落 F01 */
    public static final float CALM_THRESHOLD = 0.5f;

    /** 参与外显的情绪维度（不含 calm，calm 由 F01 承载） */
    public static final String[] EXPRESSIVE = {"happy", "sad", "angry", "surprised", "shy"};

    /** 平静表情（与 {@code PetOverlayView#mapExpression} 的默认分支一致） */
    public static final String CALM_EXPRESSION = "F01";

    private String current = CALM_EXPRESSION;
    private String currentDim = "calm";
    private long lastChangeMs = Long.MIN_VALUE;

    /** 当前应显示的表情 */
    public String current() {
        return current;
    }

    /**
     * 推进一步。
     *
     * @return 需要应用的新表情；维持不变时返回 {@code null}（调用方无需刷 UI）
     */
    public String update(EmotionState emo, long nowMs) {
        if (emo == null) return null;
        String target = targetFor(emo);
        if (target.equals(current)) return null;
        // 最短停留
        if (lastChangeMs != Long.MIN_VALUE && nowMs - lastChangeMs < MIN_DWELL_MS) return null;
        // 迟滞：切离平静或换到另一个非平静表情，都要求目标情绪明显领先
        if (!CALM_EXPRESSION.equals(target)) {
            float targetV = emo.get(dimOf(target));
            float curV = emo.get(currentDim);
            if (targetV < curV + SWITCH_MARGIN) return null;
        }
        current = target;
        currentDim = dimOf(target);
        lastChangeMs = nowMs;
        return current;
    }

    /**
     * 交互（用户点击 / 对话 / 手动换表情）覆盖后对齐内部状态：
     * 让自主表情把 {@code expression} 当作「当前值」，避免下一 tick 立刻抢回去。
     */
    public void syncCurrent(String expression, long nowMs) {
        if (expression == null) return;
        current = expression;
        currentDim = dimOf(expression);
        lastChangeMs = nowMs;
    }

    /** 情绪向量 → 目标表情（无迟滞，供测试与同步判定用） */
    public static String targetFor(EmotionState emo) {
        if (emo == null) return CALM_EXPRESSION;
        String best = null;
        float bestV = 0f;
        for (String d : EXPRESSIVE) {
            float v = emo.get(d);
            if (v > bestV) {
                bestV = v;
                best = d;
            }
        }
        if (best == null || bestV < CALM_THRESHOLD) return CALM_EXPRESSION;
        // 平静值占优时不外显情绪，保持 F01
        if (bestV <= emo.get("calm")) return CALM_EXPRESSION;
        return expressionFor(best);
    }

    /** 情绪维度 → Live2D 表情名（编号与 {@code PetOverlayView#mapExpression} 一致） */
    public static String expressionFor(String dim) {
        if (dim == null) return CALM_EXPRESSION;
        switch (dim) {
            case "happy":
            case "excited":
                return "F02";
            case "sad":
                return "F03";
            case "angry":
                return "F04";
            case "surprised":
                return "F05";
            case "shy":
                return "F06";
            default:
                return CALM_EXPRESSION;
        }
    }

    /** 表情名 → 情绪维度；未知 / F01 一律映射到 calm */
    public static String dimOf(String expression) {
        if (expression == null) return "calm";
        switch (expression) {
            case "F02":
                return "happy";
            case "F03":
                return "sad";
            case "F04":
                return "angry";
            case "F05":
                return "surprised";
            case "F06":
                return "shy";
            default:
                return "calm";
        }
    }

    /**
     * 表情变化时配套的自主小动作（动作名，交给 {@code PetOverlayView#resolveMotion}）；
     * 平静 / 生气不外显动作，返回 {@code null}。
     */
    public static String motionFor(String expression) {
        if (expression == null) return null;
        switch (expression) {
            case "F02":
                return "happy";
            case "F03":
                return "sad";
            case "F05":
                return "surprised";
            case "F06":
                return "tilt_head";
            default:
                return null;
        }
    }
}
