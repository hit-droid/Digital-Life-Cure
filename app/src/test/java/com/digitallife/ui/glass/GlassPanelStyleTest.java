package com.digitallife.ui.glass;

import com.digitallife.render.GlassNative;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

/**
 * {@link GlassPanelStyle} → native 参数数组的映射回归。
 *
 * <p>纯 Java 逻辑，不需要 Robolectric / GL：只断言「dp → px」换算与下标对齐。
 * 这类映射一旦错位，症状是玻璃面板整体偏移、圆角方向反了或采样到了别处，
 * 在真机上很难肉眼归因，所以用单测钉住。</p>
 */
public class GlassPanelStyleTest {

    private static final float EPS = 0.001f;

    /** 只关心面板矩形/圆角的用例：背景快照参数用「与面板 1:1」的默认约定填。 */
    private static float[] params(float density, float w, float h) {
        return new GlassPanelStyle().toParams(density, 0, 0, w, h, w, h, w, h, false, 0, 0);
    }

    @Test
    public void drawer_onlyLeftCornersRounded() {
        // 抽屉靠屏幕左边缘，只有左侧两角圆角；右侧必须平齐（否则右缘露出缺口）
        float[] p = GlassPanelStyle.drawer().toParams(3f, 0, 0, 840, 2400, 840, 2400, 840, 2400, false, 0, 0);
        assertEquals(16f * 3, p[GlassNative.P_RADIUS_TL], EPS);
        assertEquals(0f, p[GlassNative.P_RADIUS_TR], EPS);
        assertEquals(0f, p[GlassNative.P_RADIUS_BR], EPS);
        assertEquals(16f * 3, p[GlassNative.P_RADIUS_BL], EPS);
    }

    @Test
    public void drawer_matchesShadowDrawableRadius() {
        // 抽屉玻璃圆角必须与 bg_operit_drawer(_glass) 的 16dp 一致，否则轮廓与面板错位
        GlassPanelStyle s = GlassPanelStyle.drawer();
        assertEquals(16f, s.radiusTlDp, EPS);
        assertEquals(16f, s.radiusBlDp, EPS);
    }

    @Test
    public void nav_fourCornersMatchCapsuleRadius() {
        // 导航玻璃圆角必须与 bg_operit_bottom_nav(_glass) 的 28dp 一致，且四角同值
        GlassPanelStyle s = GlassPanelStyle.nav();
        assertEquals(28f, s.radiusTlDp, EPS);
        assertEquals(28f, s.radiusTrDp, EPS);
        assertEquals(28f, s.radiusBrDp, EPS);
        assertEquals(28f, s.radiusBlDp, EPS);
        float[] p = s.toParams(3f, 0, 0, 1200, 180, 1200, 180, 1200, 180, false, 0, 0);
        assertEquals(28f * 3, p[GlassNative.P_RADIUS_TL], EPS);
        assertEquals(28f * 3, p[GlassNative.P_RADIUS_BR], EPS);
    }

    @Test
    public void panelRectAndCaptureOriginArePixelAligned() {
        // 主壳约定：快照区域 == 玻璃层矩形，故 origin 恒为 (0,0)，panel 内坐标与快照 1:1
        float[] p = params(2f, 560, 1600);
        assertEquals(560f, p[GlassNative.P_W], EPS);
        assertEquals(1600f, p[GlassNative.P_H], EPS);
        assertEquals(0f, p[GlassNative.P_CAPTURE_ORIGIN_X], EPS);
        assertEquals(0f, p[GlassNative.P_CAPTURE_ORIGIN_Y], EPS);
        assertEquals(560f, p[GlassNative.P_CAPTURE_W], EPS);
        assertEquals(1600f, p[GlassNative.P_CAPTURE_H], EPS);
    }

    @Test
    public void dpSizesScaleWithDensity() {
        float[] mdpi = params(1f, 100, 100);
        float[] xxhdpi = params(3f, 300, 300);
        assertEquals(18f, mdpi[GlassNative.P_REFRACTION_HEIGHT], EPS);
        assertEquals(18f * 3, xxhdpi[GlassNative.P_REFRACTION_HEIGHT], EPS);
        assertEquals(12f, mdpi[GlassNative.P_BLUR], EPS);
        assertEquals(12f * 3, xxhdpi[GlassNative.P_BLUR], EPS);
    }

    @Test
    public void jniIndexConstantsStayInSyncWithParamCount() {
        // 下标是 Java ↔ JNI 的硬契约：任何一处错位都会静默画错
        assertEquals(GlassNative.PARAM_COUNT, params(1f, 10, 10).length);
        assertEquals(GlassNative.PARAM_COUNT - 1, GlassNative.P_FALLBACK_A);
    }
}
