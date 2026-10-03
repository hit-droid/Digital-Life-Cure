package com.digitallife.util;

/**
 * 桌宠悬浮窗「边缘停靠」纯逻辑（v1.132.0）。
 *
 * <p>桌宠松手后把窗口吸附到最近的左/右边缘，是桌面惯例：既少挡内容，又能一眼看到角色。
 * 这里不碰任何 Android API，只做几何计算，便于在 JVM 上做单测。
 */
public final class OverlayDock {

    /** 停靠后与屏幕边缘的留白（px） */
    public static final int EDGE_MARGIN = 8;

    /** 停靠侧 */
    public enum Side { NONE, LEFT, RIGHT }

    /** 停靠结果：吸附后的坐标 + 停靠侧 */
    public static final class Result {
        public final int x;
        public final int y;
        public final Side side;

        public Result(int x, int y, Side side) {
            this.x = x;
            this.y = y;
            this.side = side;
        }
    }

    private OverlayDock() {
    }

    /**
     * 计算停靠 / 约束后的窗口位置。
     *
     * <p>规则：
     * <ul>
     *   <li>纵向始终夹在屏幕内（窗口比屏幕高时贴顶）；</li>
     *   <li>{@code enabled=false} 时只做屏幕内约束，返回 {@link Side#NONE}；</li>
     *   <li>开启时以「窗口中心 vs 屏幕中心」判断吸附到左还是右，留 {@link #EDGE_MARGIN} 白边；</li>
     *   <li>窗口宽度贴满/超出屏幕时留白退化为 0，不会把窗口推到屏外。</li>
     * </ul>
     */
    public static Result dock(int x, int y, int viewW, int viewH,
                              int screenW, int screenH, boolean enabled) {
        int w = Math.max(0, viewW);
        int h = Math.max(0, viewH);
        int sw = Math.max(0, screenW);
        int sh = Math.max(0, screenH);

        int cy = clamp(y, 0, Math.max(0, sh - h));
        int maxX = Math.max(0, sw - w);

        if (!enabled) {
            return new Result(clamp(x, 0, maxX), cy, Side.NONE);
        }

        boolean toLeft = x + w / 2f < sw / 2f;
        int margin = Math.min(EDGE_MARGIN, maxX);
        int dockX = toLeft ? margin : maxX - margin;
        return new Result(clamp(dockX, 0, maxX), cy, toLeft ? Side.LEFT : Side.RIGHT);
    }

    private static int clamp(int v, int lo, int hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }
}
