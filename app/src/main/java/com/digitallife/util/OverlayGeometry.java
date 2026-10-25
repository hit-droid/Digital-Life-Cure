package com.digitallife.util;

/**
 * v1.189.0：桌宠悬浮窗几何（纯逻辑，便于单测）。
 *
 * <p>此前窗口尺寸的 {@code 0.35f / 0.57f} 在 {@code PetService} 里写了两遍
 * （启动与旋转重建），人偶触摸带又单独写了一套 {@code 0.2f~0.8f}。三处魔数
 * 各改各的，结果就是「桌宠在桌面上只占一条窄带」。这里统一成一份可测的几何：
 * 窗口占屏幕的比例、缩放上下限、触摸带范围。</p>
 */
public final class OverlayGeometry {

    /** 窗口基准宽度（占屏幕宽），实际再乘缩放；旧版是 0.35f 的窄条 */
    public static final float WIDTH_RATIO = 0.48f;
    /** 窗口基准高度（占屏幕高），实际再乘缩放；旧版是 0.57f */
    public static final float HEIGHT_RATIO = 0.62f;

    public static final int SCALE_MIN = 70;
    public static final int SCALE_MAX = 130;
    public static final int SCALE_DEFAULT = 100;

    /** 触摸带左右留白（占窗口宽）；10% 起、90% 止，即中间 80% 宽 */
    public static final float TOUCH_LEFT = 0.10f;
    public static final float TOUCH_RIGHT = 0.90f;
    /** 触摸带顶部留白（占窗口高），底部贴到窗口下沿 */
    public static final float TOUCH_TOP = 0.10f;

    private OverlayGeometry() {
    }

    public static int clampScale(int scalePercent) {
        if (scalePercent < SCALE_MIN) return SCALE_MIN;
        if (scalePercent > SCALE_MAX) return SCALE_MAX;
        return scalePercent;
    }

    public static int windowWidth(int screenWidth, int scalePercent) {
        return Math.max(1, Math.round(Math.max(0, screenWidth) * WIDTH_RATIO
                * clampScale(scalePercent) / 100f));
    }

    public static int windowHeight(int screenHeight, int scalePercent) {
        return Math.max(1, Math.round(Math.max(0, screenHeight) * HEIGHT_RATIO
                * clampScale(scalePercent) / 100f));
    }

    /**
     * 可触摸的人偶带：{left, top, right, bottom}。
     * 带外的事件透传给下层应用，带内才响应点击 / 拖动。
     */
    public static int[] touchBand(int w, int h) {
        int l = Math.max(0, Math.round(w * TOUCH_LEFT));
        int t = Math.max(0, Math.round(h * TOUCH_TOP));
        int r = Math.min(w, Math.round(w * TOUCH_RIGHT));
        return new int[]{l, t, r, h};
    }
}
