package com.digitallife.ui.pet;

/**
 * 桌宠状态胶囊的文案拼装（AGENTS.md Issue #27 第 1 条）。
 *
 * <p>数据来自 {@code brain/EmotionState}（亲密度 / 精力 / 主导情绪），但这层
 * 只收三个标量，不认识 EmotionState 也不依赖 Android——方便单测，也避免
 * {@code ui/} 反向依赖 {@code brain/}。</p>
 *
 * <p>情绪中文用词与 {@code EmotionState#describe()} 保持一致，
 * 免得同一份状态在桌宠和「发现」页上两套叫法。</p>
 */
public final class PetStatusText {

    /**
     * 详情面板要展示的六维情绪，顺序与 {@code brain/EmotionState.DIMS} 保持一致。
     *
     * <p>{@code ui/} 不依赖 {@code brain/}，所以这里复制一份常量而不是引用；
     * 两边顺序对齐即可，改了一边记得改另一边。</p>
     */
    public static final String[] DIMS = {"happy", "sad", "angry", "surprised", "shy", "calm"};

    private PetStatusText() {
    }

    /** 主导情绪的中文标签；无法识别时回退「平静」 */
    public static String dominantLabel(String dim) {
        if (dim == null) return "平静";
        switch (dim) {
            case "happy":
                return "开心";
            case "sad":
                return "难过";
            case "angry":
                return "生气";
            case "surprised":
                return "惊讶";
            case "shy":
                return "害羞";
            case "calm":
                return "平静";
            default:
                return "平静";
        }
    }

    /** 0~1 归一成 0~100 的百分比；越界夹取，NaN 视作 0 */
    public static int pct(float v) {
        if (Float.isNaN(v)) return 0;
        float c = v < 0f ? 0f : (v > 1f ? 1f : v);
        return Math.round(c * 100f);
    }

    /**
     * 胶囊文案：{@code 亲密度 62% · 精力 71% · 开心}。
     * <p>桌宠上方寸之地，只放这三个最有用的量，不堆六维情绪向量。</p>
     */
    public static String capsule(float intimacy, float energy, String dominant) {
        return "亲密度 " + pct(intimacy) + "% · 精力 " + pct(energy) + "% · "
                + dominantLabel(dominant);
    }

    // ==================== v1.136.0：点开详情（Issue #27 第 1 条收尾） ====================

    /**
     * 详情面板标题：{@code 小汐的状态}。
     *
     * <p>角色名可由用户改（{@code Settings.pet_name}），空名回落「我的状态」，
     * 免得渲染出「的状态」这种半截文案。名字超长时按字符截断，防止顶破卡片。</p>
     */
    public static String detailTitle(String petName) {
        String n = petName == null ? "" : petName.trim();
        if (n.isEmpty()) return "我的状态";
        if (n.length() > 8) n = n.substring(0, 8);
        return n + "的状态";
    }

    /**
     * 详情面板副标题：{@code 亲密度 62% · 亲密}。
     */
    public static String detailSubtitle(float intimacy) {
        return "亲密度 " + pct(intimacy) + "% · " + bondLevel(intimacy);
    }

    /**
     * 亲密度分级：陌生 / 眼熟 / 朋友 / 亲密 / 挚友。
     *
     * <p>阈值按 20% 一档均分。需要给用户一个「关系进展」的直观说法，
     * 纯百分比看不出远近。</p>
     */
    public static String bondLevel(float intimacy) {
        int p = pct(intimacy);
        if (p < 20) return "陌生";
        if (p < 40) return "眼熟";
        if (p < 60) return "朋友";
        if (p < 80) return "亲密";
        return "挚友";
    }

    /**
     * 详情面板里进度条的填充宽度（px）。
     *
     * <p>把 0~1 映射到 {@code [0, maxPx]}；NaN 视作 0，越界夹取。
     * 必须保证「永不超过 maxPx、永不为负」——进度条画宽了会顶出卡片，
     * 画成负数在 View 上直接抛异常。</p>
     */
    public static int barWidth(float v, int maxPx) {
        if (maxPx <= 0) return 0;
        float c = Float.isNaN(v) ? 0f : (v < 0f ? 0f : (v > 1f ? 1f : v));
        int w = Math.round(c * maxPx);
        return w < 0 ? 0 : (w > maxPx ? maxPx : w);
    }

    /** 详情面板里的一行数值：{@code 62%}，与进度条同源，保证「条形长度」和「数字」不会各说各话 */
    public static String rowValue(float v) {
        return pct(v) + "%";
    }
}
