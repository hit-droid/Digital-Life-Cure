package com.digitallife.ui.chat;

/**
 * 聊天页布局的纯尺寸/边距计算（#96 聊天页 Operit 化收口时抽出）。
 *
 * <p>此前这些数值散落在 ChatActivity（3000+ 行）里当魔法常量用，
 * 而顶栏留白在 {@code UiKit.pageTopBar} 里还另有一份同名约定 —— 历史上已经因为
 * 「两份各写一份、改一处漏另一处」出过「标题被状态栏压住」的问题。这里把约定集中
 * 定义并配单测，ChatActivity 与 UiKit 都来这里取值。</p>
 *
 * <p>本类不依赖任何 Android API，可在普通 JVM 单测中直接运行。</p>
 */
public final class ChatLayoutOps {

    private ChatLayoutOps() {
    }

    /** 气泡最大宽度占屏幕宽度的比例（沿用 v1.26.0 起的既有表现，抽出以便回归） */
    public static final float BUBBLE_MAX_WIDTH_RATIO = 0.82f;

    /**
     * Operit 圆形图标按钮直径（dp）。
     * <p>与 {@code UiKit.pageTopBar} 的返回按钮同值，保证聊天页顶栏与二级页顶栏
     * 视觉一致。</p>
     */
    public static final int ICON_BUTTON_DP = 36;

    /** 顶栏基础上下内边距（dp），状态栏高度在此之上叠加 */
    public static final int TOP_BAR_BASE_PADDING_DP = 10;

    /** 气泡横向内边距（dp） */
    public static final int BUBBLE_PADDING_H_DP = 14;

    /** 气泡纵向内边距（dp） */
    public static final int BUBBLE_PADDING_V_DP = 10;

    /**
     * 气泡最大宽度（px）。
     *
     * <p>屏宽 × {@link #BUBBLE_MAX_WIDTH_RATIO} 后取整。结果为 0 或负时兜底为 1px：
     * 极端窄屏 / 分屏下若得到 0，气泡会被压成不可见。</p>
     */
    public static int maxBubbleWidthPx(int screenWidthPx) {
        int w = (int) (screenWidthPx * BUBBLE_MAX_WIDTH_RATIO);
        return w < 1 ? 1 : w;
    }

    /**
     * 顶栏顶部内边距（px）：系统状态栏高度 + 基础留白。
     *
     * <p>取不到状态栏高度时（0）仍保留基础留白；负数兜底为 0，避免某些 ROM 返回
     * 异常值时把内容顶出屏幕。</p>
     */
    public static int topBarPaddingTopPx(int statusBarHeightPx, int basePaddingPx) {
        int v = statusBarHeightPx + basePaddingPx;
        return v < 0 ? 0 : v;
    }
}
