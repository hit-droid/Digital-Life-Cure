package com.digitallife.ui.glass;

import com.digitallife.render.GlassNative;

/**
 * 液态玻璃面板样式 → native 着色器参数的纯逻辑映射。
 *
 * <p>本类不碰 Android 框架、不碰 GL：只把「dp 尺寸 + 颜色」换算成
 * {@link GlassNative} 要求的定长 float[]（下标见 {@code GlassNative.P_*}），
 * 便于单元测试；真正的绘制在 {@code render/LiquidGlassLayerView} 的 GL 线程里。</p>
 *
 * <p>单位约定：样式里一律是 dp / 无量纲；输出一律是像素。</p>
 */
public final class GlassPanelStyle {

    /** 四角圆角半径（dp）：左上 / 右上 / 右下 / 左下。抽屉只圆左侧（靠屏幕边），右侧平齐 */
    public float radiusTlDp = 22f;
    public float radiusTrDp = 22f;
    public float radiusBrDp = 22f;
    public float radiusBlDp = 22f;
    /** 边缘折射带宽度（dp）：进入面板多少距离内开始弯折 */
    public float refractionHeightDp = 18f;
    /** 最大折射位移（dp）：越大「透镜感」越强 */
    public float refractionAmountDp = 6f;
    /** 色散强度 0~1：边缘彩虹 */
    public float dispersion = 0.3f;
    /** 背景模糊半径（dp） */
    public float blurDp = 12f;
    /** 整体透明度 0~1 */
    public float opacity = 1f;
    /** 鲜艳度：>0.5 时背景饱和度 ×1.5 */
    public float vibrancy = 1f;
    public float brightness = 0f;
    public float contrast = 1f;
    public float saturation = 1f;

    /** 表面染色（ARGB），tintMode=2 时按 alpha 与背景混合 */
    public int surfaceColor = 0x33FFFFFF;
    /** 0 不染 / 1 luma 着色 / 2 直接混合 */
    public float tintMode = 2f;

    public int highlightColor = 0x80FFFFFF;
    public float highlightAlpha = 0.5f;
    public float highlightWidthDp = 0.5f;
    public float highlightBlurDp = 0.25f;
    public float highlightAngle = 45f;
    public float highlightFalloff = 1f;
    /** 0 关 / 1 加色 / 2 混白 / 3 环境光 */
    public float highlightMode = 1f;

    public int innerShadowColor = 0x26000000;
    public float innerShadowAlpha = 0.6f;
    public float innerShadowRadiusDp = 24f;
    public float innerShadowOffsetYDp = 12f;

    /** 无背景纹理时的兜底底色（ARGB） */
    public int fallbackColor = 0xFF1F1C2C;

    /** 主壳侧栏抽屉：左侧 16dp 圆角（与 bg_operit_drawer 一致）、强折射、紫调染色 */
    public static GlassPanelStyle drawer() {
        GlassPanelStyle s = new GlassPanelStyle();
        s.radiusTlDp = 16f;
        s.radiusTrDp = 0f;
        s.radiusBrDp = 0f;
        s.radiusBlDp = 16f;
        s.refractionHeightDp = 26f;
        s.refractionAmountDp = 10f;
        s.dispersion = 0.35f;
        s.blurDp = 16f;
        s.surfaceColor = 0x402A2640;
        s.tintMode = 2f;
        s.fallbackColor = 0xFF1A1726;
        return s;
    }

    /** 底部导航胶囊：圆角与 bg_operit_bottom_nav 的 28dp 一致，中等折射 */
    public static GlassPanelStyle nav() {
        GlassPanelStyle s = new GlassPanelStyle();
        s.radiusTlDp = 28f;
        s.radiusTrDp = 28f;
        s.radiusBrDp = 28f;
        s.radiusBlDp = 28f;
        s.refractionHeightDp = 14f;
        s.refractionAmountDp = 6f;
        s.dispersion = 0.25f;
        s.blurDp = 10f;
        s.surfaceColor = 0x332A2640;
        s.tintMode = 2f;
        return s;
    }

    /**
     * 生成 native 参数数组。
     *
     * @param density       dp → px 倍率
     * @param x,y,w,h       面板矩形（surface 像素，原点左上）
     * @param captureW/H    背景快照的像素尺寸（0 表示无背景）
     * @param texW/H        背景纹理的存储尺寸（分块纹理需 ≥ 快照尺寸）
     * @param flipY         背景是否为帧缓冲拷贝（GL 原点在左下，需要翻转 v）
     * @param originX/Y     面板左上角在快照中的像素偏移
     */
    public float[] toParams(float density, float x, float y, float w, float h,
                            float captureW, float captureH, float texW, float texH,
                            boolean flipY, float originX, float originY) {
        if (density <= 0f) density = 1f;
        float[] p = new float[GlassNative.PARAM_COUNT];
        p[GlassNative.P_X] = x;
        p[GlassNative.P_Y] = y;
        p[GlassNative.P_W] = w;
        p[GlassNative.P_H] = h;

        p[GlassNative.P_RADIUS_TL] = radiusTlDp * density;
        p[GlassNative.P_RADIUS_TR] = radiusTrDp * density;
        p[GlassNative.P_RADIUS_BR] = radiusBrDp * density;
        p[GlassNative.P_RADIUS_BL] = radiusBlDp * density;

        p[GlassNative.P_REFRACTION_HEIGHT] = refractionHeightDp * density;
        p[GlassNative.P_REFRACTION_AMOUNT] = refractionAmountDp * density;
        p[GlassNative.P_DISPERSION] = clamp01(dispersion);
        p[GlassNative.P_BLUR] = blurDp * density;

        p[GlassNative.P_OPACITY] = clamp01(opacity);
        p[GlassNative.P_VIBRANCY] = vibrancy;
        p[GlassNative.P_BRIGHTNESS] = brightness;
        p[GlassNative.P_CONTRAST] = contrast;
        p[GlassNative.P_SATURATION] = saturation;

        p[GlassNative.P_SURFACE_R] = red(surfaceColor);
        p[GlassNative.P_SURFACE_G] = green(surfaceColor);
        p[GlassNative.P_SURFACE_B] = blue(surfaceColor);
        p[GlassNative.P_SURFACE_A] = alpha(surfaceColor);
        p[GlassNative.P_TINT_MODE] = tintMode;

        p[GlassNative.P_HL_R] = red(highlightColor);
        p[GlassNative.P_HL_G] = green(highlightColor);
        p[GlassNative.P_HL_B] = blue(highlightColor);
        p[GlassNative.P_HL_A] = alpha(highlightColor);
        p[GlassNative.P_HL_ALPHA] = clamp01(highlightAlpha);
        p[GlassNative.P_HL_WIDTH] = highlightWidthDp * density;
        p[GlassNative.P_HL_BLUR] = highlightBlurDp * density;
        p[GlassNative.P_HL_ANGLE] = (float) Math.toRadians(highlightAngle);
        p[GlassNative.P_HL_FALLOFF] = highlightFalloff;
        p[GlassNative.P_HL_MODE] = highlightMode;

        p[GlassNative.P_INNER_R] = red(innerShadowColor);
        p[GlassNative.P_INNER_G] = green(innerShadowColor);
        p[GlassNative.P_INNER_B] = blue(innerShadowColor);
        p[GlassNative.P_INNER_A] = alpha(innerShadowColor);
        p[GlassNative.P_INNER_ALPHA] = clamp01(innerShadowAlpha);
        p[GlassNative.P_INNER_RADIUS] = innerShadowRadiusDp * density;
        p[GlassNative.P_INNER_OFFSET_X] = 0f;
        p[GlassNative.P_INNER_OFFSET_Y] = innerShadowOffsetYDp * density;

        p[GlassNative.P_CAPTURE_ORIGIN_X] = originX;
        p[GlassNative.P_CAPTURE_ORIGIN_Y] = originY;
        p[GlassNative.P_CAPTURE_W] = captureW;
        p[GlassNative.P_CAPTURE_H] = captureH;
        p[GlassNative.P_CAPTURE_TEX_W] = texW > 0 ? texW : captureW;
        p[GlassNative.P_CAPTURE_TEX_H] = texH > 0 ? texH : captureH;
        p[GlassNative.P_FLIP_Y] = flipY ? 1f : 0f;

        p[GlassNative.P_FALLBACK_R] = red(fallbackColor);
        p[GlassNative.P_FALLBACK_G] = green(fallbackColor);
        p[GlassNative.P_FALLBACK_B] = blue(fallbackColor);
        p[GlassNative.P_FALLBACK_A] = alpha(fallbackColor);
        return p;
    }

    private static float clamp01(float v) {
        if (v < 0f) return 0f;
        return v > 1f ? 1f : v;
    }

    private static float red(int argb) { return ((argb >> 16) & 0xFF) / 255f; }

    private static float green(int argb) { return ((argb >> 8) & 0xFF) / 255f; }

    private static float blue(int argb) { return (argb & 0xFF) / 255f; }

    private static float alpha(int argb) { return ((argb >>> 24) & 0xFF) / 255f; }
}
