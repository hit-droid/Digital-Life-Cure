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
}
