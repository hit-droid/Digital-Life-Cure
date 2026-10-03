package com.digitallife.ui.chat;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * {@link ScrollAnchor} 的回归（issue #43）。
 *
 * <p>「上翻之后没有回到底部的入口、期间到了几条回复也没提示」是长会话里最扎眼的
 * 体验缺口。这个类是该浮标的纯逻辑部分：不碰 View、不碰持久化，所以本地 JVM
 * 直接能跑，不需要 Robolectric。</p>
 */
public class ScrollAnchorTest {

    // ==================== shouldShow：阈值边界 ====================

    @Test
    public void shouldShow_belowThresholdIsFalse() {
        assertFalse(ScrollAnchor.shouldShow(0f, 140));
        assertFalse(ScrollAnchor.shouldShow(139f, 140));
    }

    @Test
    public void shouldShow_atThresholdIsFalse() {
        // 正好等于阈值算「还在底部」，否则会在边界上反复抖动
        assertFalse(ScrollAnchor.shouldShow(140f, 140));
    }

    @Test
    public void shouldShow_aboveThresholdIsTrue() {
        assertTrue(ScrollAnchor.shouldShow(141f, 140));
        assertTrue(ScrollAnchor.shouldShow(5000f, 140));
    }

    @Test
    public void shouldShow_zeroThreshold() {
        assertFalse(ScrollAnchor.shouldShow(0f, 0));
        assertTrue(ScrollAnchor.shouldShow(1f, 0));
    }

    // ==================== 未读累加 / 清零 ====================

    @Test
    public void unread_startsAtZero() {
        assertEquals(0, new ScrollAnchor().unreadCount());
    }

    @Test
    public void unread_accumulatesWhenAwayFromBottom() {
        ScrollAnchor a = new ScrollAnchor();
        a.onMessageAppended(false);
        a.onMessageAppended(false);
        a.onMessageAppended(false);
        assertEquals(3, a.unreadCount());
    }

    @Test
    public void unread_atBottomDoesNotAccumulate() {
        // 用户正停在底部：新消息看得见，不该算未读
        ScrollAnchor a = new ScrollAnchor();
        a.onMessageAppended(true);
        a.onMessageAppended(true);
        assertEquals(0, a.unreadCount());
    }

    @Test
    public void unread_atBottomClearsEarlierCount() {
        // 攒了几条之后回到底部再看：旧计数要清掉，否则下次上翻会显示过期的数字
        ScrollAnchor a = new ScrollAnchor();
        a.onMessageAppended(false);
        a.onMessageAppended(false);
        assertEquals(2, a.unreadCount());
        a.onMessageAppended(true);
        assertEquals(0, a.unreadCount());
    }

    @Test
    public void clear_resetsCount() {
        ScrollAnchor a = new ScrollAnchor();
        a.onMessageAppended(false);
        a.onMessageAppended(false);
        a.clear();
        assertEquals(0, a.unreadCount());
    }

    // ==================== label 文案 ====================

    @Test
    public void label_zeroIsBackToBottom() {
        assertEquals("回到底部", ScrollAnchor.label(0));
    }

    @Test
    public void label_negativeIsBackToBottom() {
        assertEquals("回到底部", ScrollAnchor.label(-5));
    }

    @Test
    public void label_oneIsSingular() {
        assertEquals("1 条新消息", ScrollAnchor.label(1));
    }

    @Test
    public void label_smallCount() {
        assertEquals("7 条新消息", ScrollAnchor.label(7));
    }

    @Test
    public void label_atMaxShowsExactNumber() {
        assertEquals("99 条新消息", ScrollAnchor.label(ScrollAnchor.MAX_LABEL_COUNT));
    }

    @Test
    public void label_aboveMaxShows99Plus() {
        // 上限显示 99+：按钮宽度有限，三位数往上都压成 99+
        assertEquals("99+ 条新消息", ScrollAnchor.label(100));
        assertEquals("99+ 条新消息", ScrollAnchor.label(1000));
    }

    @Test
    public void label_neverExceedsMaxAfterAccumulating() {
        ScrollAnchor a = new ScrollAnchor();
        for (int i = 0; i < 150; i++) a.onMessageAppended(false);
        assertEquals(150, a.unreadCount());
        assertEquals("99+ 条新消息", ScrollAnchor.label(a.unreadCount()));
    }
}
