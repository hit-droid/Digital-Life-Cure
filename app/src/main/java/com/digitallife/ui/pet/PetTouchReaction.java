package com.digitallife.ui.pet;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * 桌宠触摸分区反应（Issue #37，纯逻辑，JVM 可测）。
 *
 * <p>分区取值为 {@code "Head"} / {@code "Body"} / {@code ""}，与 Cubism 的
 * {@code HitAreaNameHead} / {@code HitAreaNameBody} 对齐——将来若把原生
 * {@code LAppModel::HitTest} 的结果直出到 Java，这里不用改就接得上。</p>
 *
 * <h3>为什么是 Java 近似，而不是原生 HitArea</h3>
 * <p>{@code ui/PetOverlayView.onInterceptTouchEvent} 在 ACTION_DOWN 命中人偶带时就把事件拦截了，
 * 子 View {@code render/Live2DGLView} 只会收到 ACTION_CANCEL，{@code nativeOnTouchesBegan}
 * 从不触发，原生 {@code HitTest} 这条线在悬浮窗里是死的；跨线程去 GL 线程读 viewMatrix
 * 做命中又会引入竞态。因此这里按触摸点在人偶区域内的<b>纵向位置</b>切分：
 * 上 {@link #HEAD_RATIO} 视为头，其余视为身体。</p>
 *
 * <p><b>已知误差</b>：不同模型头身比例、缩放不同，这条切分线是固定值，不随模型自适应；
 * 宁可偏「头」也不偏「身」——摸头是桌宠最该有反馈的动作。若日后原生直出命中结果，
 * 替换 {@link #zoneFor} 一个方法即可，{@link #reactionFor} 与接线都不用动。</p>
 */
public final class PetTouchReaction {

    /** 命中头部（对齐 Cubism HitAreaNameHead） */
    public static final String ZONE_HEAD = "Head";
    /** 命中身体（对齐 Cubism HitAreaNameBody） */
    public static final String ZONE_BODY = "Body";
    /** 未命中任何分区（落在人偶区域之外 / 区域退化） */
    public static final String ZONE_NONE = "";

    /** 头部判定线：人偶区域内上 38% 视为头 */
    public static final float HEAD_RATIO = 0.38f;

    /** 摸头：害羞撒娇。F06 与 {@code PetOverlayView#mapExpression} 的 shy 一致 */
    private static final Reaction HEAD = new Reaction(
            "唔…别揉啦，头发都乱啦~", "F06", "Shy", 0, 0.02f,
            emotions("shy", 0.18f, "happy", 0.06f));

    /** 戳身子：惊讶躲一下 */
    private static final Reaction BODY = new Reaction(
            "呀！好痒，别戳那里~", "F05", "TapBody", -1, 0.01f,
            emotions("surprised", 0.15f, "happy", 0.04f));

    /** 未知分区兜底：不改表情、不出气泡、不动情绪，只保留原有的随机 TapBody 动作 */
    private static final Reaction NONE = new Reaction(
            null, null, "TapBody", -1, 0f, Collections.<String, Float>emptyMap());

    private PetTouchReaction() {
    }

    /**
     * 触摸点 → 分区。落在人偶区域外或区域尺寸退化时返回 {@link #ZONE_NONE}。
     *
     * @param left   人偶区域左边界（View 坐标）
     * @param top    人偶区域上边界
     * @param right  人偶区域右边界
     * @param bottom 人偶区域下边界
     */
    public static String zoneFor(float x, float y, int left, int top, int right, int bottom) {
        if (right <= left || bottom <= top) return ZONE_NONE;
        if (x < left || x > right || y < top || y > bottom) return ZONE_NONE;
        float ratio = (y - top) / (float) (bottom - top);
        return ratio < HEAD_RATIO ? ZONE_HEAD : ZONE_BODY;
    }

    /** 分区 → 反馈；null / 未知分区一律走兜底，不抛异常 */
    public static Reaction reactionFor(String zone) {
        if (ZONE_HEAD.equals(zone)) return HEAD;
        if (ZONE_BODY.equals(zone)) return BODY;
        return NONE;
    }

    private static Map<String, Float> emotions(String dim1, float v1, String dim2, float v2) {
        Map<String, Float> m = new LinkedHashMap<>();
        m.put(dim1, v1);
        m.put(dim2, v2);
        return m;
    }

    /** 一次分区互动的反馈：气泡文案 / 表情 / 动作 / 亲密度与情绪增量。 */
    public static final class Reaction {
        /** 气泡文案；null 表示不出气泡 */
        public final String bubble;
        /** Live2D 表情名（F0x）；null 表示不改表情 */
        public final String expression;
        /** 动作组名；null 表示不播动作 */
        public final String motionGroup;
        /** 动作索引；< 0 表示随机 */
        public final int motionIndex;
        /** 亲密度增量 */
        public final float intimacy;
        private final Map<String, Float> emotions;

        private Reaction(String bubble, String expression, String motionGroup, int motionIndex,
                         float intimacy, Map<String, Float> emotions) {
            this.bubble = bubble;
            this.expression = expression;
            this.motionGroup = motionGroup;
            this.motionIndex = motionIndex;
            this.intimacy = intimacy;
            this.emotions = Collections.unmodifiableMap(emotions);
        }

        /** 需要施加的情绪维度（只读） */
        public Set<String> emotionDims() {
            return emotions.keySet();
        }

        /** 某维度的情绪增量；无此维度返回 0 */
        public float emotion(String dim) {
            Float v = emotions.get(dim);
            return v == null ? 0f : v;
        }
    }
}
