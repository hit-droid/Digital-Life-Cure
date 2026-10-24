package com.digitallife.ui.pet;

/**
 * 桌宠长按快捷菜单的纯逻辑（Issue #27 第 2 条）。
 *
 * <p>菜单文案、表情循环、居中坐标都不碰 Android API，方便在 JVM 上直接跑单测；
 * 悬浮窗里那点几何一旦写错就是「桌宠飞出屏幕」，必须有回归。</p>
 *
 * <p>「一键贴边」不在这里算：{@code util/OverlayDock.dock()} 已经把贴边几何写死了，
 * 再写一份就会两套实现打架，直接调它。</p>
 */
public final class PetQuickMenu {

    /** 菜单项索引：{@code PetService} 按此 switch，不靠文案匹配，改文案不会误伤分支 */
    public static final int ITEM_EXPRESSION = 0;
    /** v1.186.0：换模型（打开模型列表二级菜单） */
    public static final int ITEM_MODEL = 1;
    /** v1.186.0：给桌宠拍照并存相册 */
    public static final int ITEM_SNAPSHOT = 2;
    public static final int ITEM_DOCK_EDGE = 3;
    public static final int ITEM_CENTER = 4;
    public static final int ITEM_LOCK = 5;
    public static final int ITEM_SETTINGS = 6;

    /** 表情循环顺序，编号与 {@code PetOverlayView#mapExpression} 的 F0x 一致 */
    public static final String[] EXPRESSION_CYCLE = {"F01", "F02", "F03", "F04", "F05", "F06"};

    private static final String[] EXPRESSION_LABELS = {"平静", "开心", "难过", "生气", "惊讶", "害羞"};

    private PetQuickMenu() {
    }

    /**
     * 长按菜单的文案列表。
     *
     * <p>锁与不锁只影响锁定那一项的措辞：锁着时是「解除锁定」，而不是再给一个「锁定位置」
     * 让用户以为没点上。</p>
     */
    public static String[] items(boolean locked) {
        return new String[]{
                "换表情",
                "换个模型",
                "拍张照",
                "一键贴边",
                "回到中间",
                locked ? "解除锁定" : "锁定位置",
                "打开设置"
        };
    }

    /**
     * v1.186.0：模型二级菜单的条目——首项固定「返回」，其后是各模型目录名。
     *
     * <p>把「返回」放第一项而不是末尾：模型多到要滚动时，末尾项会被滚出可视区，
     * 用户想退回主菜单却找不到入口。</p>
     */
    public static String[] modelMenu(String[] modelNames) {
        int n = modelNames == null ? 0 : modelNames.length;
        String[] items = new String[n + 1];
        items[0] = "返回";
        for (int i = 0; i < n; i++) {
            items[i + 1] = modelNames[i] == null ? "" : modelNames[i];
        }
        return items;
    }

    /** 循环里的下一个表情；当前值未知（null / 非 F0x）时从头开始，不会返回空 */
    public static String nextExpression(String current) {
        int idx = indexOfExpression(current);
        return EXPRESSION_CYCLE[(idx + 1) % EXPRESSION_CYCLE.length];
    }

    /** 表情的中文名，用于换表情后给一句气泡反馈；未知回落「平静」 */
    public static String expressionLabel(String expr) {
        return EXPRESSION_LABELS[indexOfExpression(expr)];
    }

    private static int indexOfExpression(String expr) {
        if (expr != null) {
            for (int i = 0; i < EXPRESSION_CYCLE.length; i++) {
                if (EXPRESSION_CYCLE[i].equals(expr)) return i;
            }
        }
        return 0;
    }

    /** 悬浮窗水平居中的 x；窗口比屏幕宽（或尺寸脏数据）时贴左，绝不返回负数 */
    public static int centerX(int viewW, int screenW) {
        return center(viewW, screenW);
    }

    /** 悬浮窗垂直居中的 y，约束同 {@link #centerX(int, int)} */
    public static int centerY(int viewH, int screenH) {
        return center(viewH, screenH);
    }

    private static int center(int viewSize, int screenSize) {
        int v = Math.max(0, viewSize);
        int s = Math.max(0, screenSize);
        int max = Math.max(0, s - v);
        int c = (s - v) / 2;
        return c < 0 ? 0 : (c > max ? max : c);
    }
}
