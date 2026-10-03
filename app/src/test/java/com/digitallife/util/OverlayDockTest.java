package com.digitallife.util;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/**
 * {@link OverlayDock} 单测（v1.132.0）：桌宠松手贴边停靠的几何规则。
 */
public class OverlayDockTest {

    private static final int SW = 1000; // 屏幕宽
    private static final int SH = 2000; // 屏幕高
    private static final int W = 200;   // 窗口宽
    private static final int H = 400;   // 窗口高

    @Test
    public void disabledOnlyClampsIntoDisplay() {
        // 未开启停靠：越界的 x/y 被夹回屏内，但不吸附
        OverlayDock.Result r = OverlayDock.dock(1500, -30, W, H, SW, SH, false);
        assertEquals(SW - W, r.x);
        assertEquals(0, r.y);
        assertEquals(OverlayDock.Side.NONE, r.side);
    }

    @Test
    public void docksToLeftEdgeWhenOnLeftHalf() {
        OverlayDock.Result r = OverlayDock.dock(100, 300, W, H, SW, SH, true);
        assertEquals(OverlayDock.EDGE_MARGIN, r.x);
        assertEquals(300, r.y);
        assertEquals(OverlayDock.Side.LEFT, r.side);
    }

    @Test
    public void docksToRightEdgeWhenOnRightHalf() {
        OverlayDock.Result r = OverlayDock.dock(800, 300, W, H, SW, SH, true);
        assertEquals(SW - W - OverlayDock.EDGE_MARGIN, r.x);
        assertEquals(OverlayDock.Side.RIGHT, r.side);
    }

    @Test
    public void exactCenterDocksRight() {
        // 中心重合时不算「偏左」，归到右侧，保证结果确定
        OverlayDock.Result r = OverlayDock.dock(400, 300, W, H, SW, SH, true);
        assertEquals(OverlayDock.Side.RIGHT, r.side);
        assertEquals(SW - W - OverlayDock.EDGE_MARGIN, r.x);
    }

    @Test
    public void clampsVerticallyIntoDisplay() {
        // 停靠只改 x，y 保持但必须夹进屏幕（底部越界 -> 贴底）
        OverlayDock.Result r = OverlayDock.dock(100, 1900, W, H, SW, SH, true);
        assertEquals(SH - H, r.y);
        assertEquals(OverlayDock.Side.LEFT, r.side);
    }

    @Test
    public void oversizedWindowDegradesToZeroMargin() {
        // 窗口比屏幕还宽：两侧的吸附位置都只能是 0，绝不能把窗口推到屏外
        assertEquals(0, OverlayDock.dock(0, 0, SW + 200, H, SW, SH, true).x);
        assertEquals(0, OverlayDock.dock(SW, 0, SW + 200, H, SW, SH, true).x);
    }

    @Test
    public void negativeSizesAreTolerated() {
        OverlayDock.Result r = OverlayDock.dock(50, 50, -10, -10, SW, SH, true);
        assertEquals(OverlayDock.EDGE_MARGIN, r.x);
        assertEquals(50, r.y);
    }
}
