package com.digitallife.ui.chat;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * {@link ChatLayoutOps} 的回归：聊天页 Operit 化收口（#96）抽出的布局常量与计算。
 *
 * <p>这些数值原先内联在 ChatActivity 里，改样式时极易顺手改坏（尤其状态栏留白
 * 历史上踩过坑）。这里锁住行为，后续调整必须显式改动常量值。</p>
 */
public class ChatLayoutOpsTest {

    // ==================== maxBubbleWidthPx ====================

    @Test
    public void maxBubbleWidthPx_commonScreenKeepsRatio() {
        assertEquals((int) (1080 * 0.82f), ChatLayoutOps.maxBubbleWidthPx(1080));
    }

    @Test
    public void maxBubbleWidthPx_smallerThanScreen() {
        assertTrue(ChatLayoutOps.maxBubbleWidthPx(1080) < 1080);
        assertTrue(ChatLayoutOps.maxBubbleWidthPx(1080) > 1080 / 2);
    }

    @Test
    public void maxBubbleWidthPx_zeroScreenNeverShrinksToZero() {
        assertEquals(1, ChatLayoutOps.maxBubbleWidthPx(0));
    }

    @Test
    public void maxBubbleWidthPx_tinyScreenNeverShrinksToZero() {
        assertEquals(1, ChatLayoutOps.maxBubbleWidthPx(1));
    }

    @Test
    public void maxBubbleWidthPx_negativeScreenNeverNegative() {
        assertEquals(1, ChatLayoutOps.maxBubbleWidthPx(-320));
    }

    // ==================== topBarPaddingTopPx ====================

    @Test
    public void topBarPaddingTopPx_addsStatusBar() {
        assertEquals(24 + 30, ChatLayoutOps.topBarPaddingTopPx(24, 30));
    }

    @Test
    public void topBarPaddingTopPx_noStatusBarKeepsBase() {
        assertEquals(30, ChatLayoutOps.topBarPaddingTopPx(0, 30));
    }

    @Test
    public void topBarPaddingTopPx_negativeNeverNegative() {
        assertEquals(0, ChatLayoutOps.topBarPaddingTopPx(-5, -10));
    }

    // ==================== 常量契约 ====================

    @Test
    public void iconButtonMatchesPageTopBarContract() {
        // 与 UiKit.pageTopBar 的圆形按钮直径保持一致：改这里必须同步改那里
        assertEquals(36, ChatLayoutOps.ICON_BUTTON_DP);
    }

    @Test
    public void baseTopPaddingMatchesPageTopBarContract() {
        assertEquals(10, ChatLayoutOps.TOP_BAR_BASE_PADDING_DP);
    }

    @Test
    public void bubblePaddingIsWiderThanTall() {
        // 气泡横向读入更多字符、纵向保持紧凑，收发两侧的视觉重量均衡
        assertTrue(ChatLayoutOps.BUBBLE_PADDING_H_DP > ChatLayoutOps.BUBBLE_PADDING_V_DP);
    }
}
