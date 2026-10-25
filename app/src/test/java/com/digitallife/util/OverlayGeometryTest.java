package com.digitallife.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * v1.189.0 {@link OverlayGeometry} 单测：桌宠窗口尺寸与触摸带。
 *
 * <p>锁住两件事：默认尺寸不再是一条窄带（宽度明显大于旧的 35%），
 * 以及缩放被夹在可用的上下限内、触摸带不越界。</p>
 */
public class OverlayGeometryTest {

    private static final int SW = 1080;
    private static final int SH = 2400;

    @Test
    public void defaultWidthIsWiderThanLegacyNarrowStrip() {
        int w = OverlayGeometry.windowWidth(SW, OverlayGeometry.SCALE_DEFAULT);
        // 旧实现是 35%，本版至少 40% 才算修掉「窄带」
        assertTrue("默认宽度应 >= 40% 屏宽，实际 " + w, w >= SW * 0.40f);
        assertEquals(Math.round(SW * OverlayGeometry.WIDTH_RATIO), w);
    }

    @Test
    public void defaultHeightMatchesRatio() {
        int h = OverlayGeometry.windowHeight(SH, OverlayGeometry.SCALE_DEFAULT);
        assertEquals(Math.round(SH * OverlayGeometry.HEIGHT_RATIO), h);
    }

    @Test
    public void scaleIsClamped() {
        assertEquals(OverlayGeometry.SCALE_MIN, OverlayGeometry.clampScale(10));
        assertEquals(OverlayGeometry.SCALE_MAX, OverlayGeometry.clampScale(999));
        assertEquals(100, OverlayGeometry.clampScale(100));
    }

    @Test
    public void widthAndHeightFollowScale() {
        int base = OverlayGeometry.windowWidth(SW, OverlayGeometry.SCALE_DEFAULT);
        int large = OverlayGeometry.windowWidth(SW, OverlayGeometry.SCALE_MAX);
        assertTrue("放大后窗口应更宽", large > base);
        // 超出上限的缩放按上限算，不会无限变大
        assertEquals(large, OverlayGeometry.windowWidth(SW, 500));
    }

    @Test
    public void windowNeverCollapsesOnTinyScreen() {
        assertEquals(1, OverlayGeometry.windowWidth(0, OverlayGeometry.SCALE_MIN));
        assertEquals(1, OverlayGeometry.windowHeight(0, OverlayGeometry.SCALE_MIN));
    }

    @Test
    public void touchBandCoversMostOfWindowButStaysInside() {
        int w = 600;
        int h = 1200;
        int[] band = OverlayGeometry.touchBand(w, h);
        assertTrue("left 不应为负", band[0] >= 0);
        assertTrue("right 不应超出窗口", band[2] <= w);
        assertEquals(h, band[3]);                         // 底部贴窗口下沿
        assertTrue("触摸带应明显宽于旧的 60%", band[2] - band[0] >= w * 0.8f);
    }

    @Test
    public void touchBandAlwaysOrdered() {
        int[] band = OverlayGeometry.touchBand(0, 0);
        assertTrue(band[0] <= band[2]);
        assertTrue(band[1] <= band[3]);
    }
}
