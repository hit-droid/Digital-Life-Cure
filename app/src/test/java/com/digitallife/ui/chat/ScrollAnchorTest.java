package com.digitallife.ui.chat;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * 「回到底部」锚点的纯逻辑回归（Issue #43）。
 *
 * <p>阈值边界与未读计数一旦算错，用户看到的就是按钮该出现时不出现、
 * 或者明明只有一条新消息却标出一大串数字。</p>
 */
public class ScrollAnchorTest {

    private static final int THRESHOLD = 140;

    @Test
    public void shouldShow_aboveThreshold() {
        assertTrue(ScrollAnchor.shouldShow(141f, THRESHOLD));
    }

    @Test
    public void shouldShow_exactlyAtThreshold_isFalse() {
        // 边界取「严格大于」，刚好贴底不算离开
        assertFalse(ScrollAnchor.shouldShow(140f, THRESHOLD));
    }

    @Test
    public void shouldShow_belowThreshold() {
        assertFalse(ScrollAnchor.shouldShow(10f, THRESHOLD));
    }

    @Test
    public void unread_zeroByDefault() {
        assertEquals(0, new ScrollAnchor().unreadCount());
    }

    @Test
    public void onMessageAppended_atBottom_doesNotCount() {
        ScrollAnchor a = new ScrollAnchor();
        a.onMessageAppended(true);
        a.onMessageAppended(true);
        assertEquals(0, a.unreadCount());
    }

    @Test
    public void onMessageAppended_away_accumulates() {
        ScrollAnchor a = new ScrollAnchor();
        a.onMessageAppended(false);
        a.onMessageAppended(false);
        a.onMessageAppended(false);
        assertEquals(3, a.unreadCount());
    }

    @Test
    public void clear_resetsUnread() {
        ScrollAnchor a = new ScrollAnchor();
        a.onMessageAppended(false);
        a.onMessageAppended(false);
        a.clear();
        assertEquals(0, a.unreadCount());
    }

    @Test
    public void label_noUnread() {
        assertEquals("回到底部", ScrollAnchor.label(0));
    }

    @Test
    public void label_singleUnread() {
        assertEquals("1 条新消息", ScrollAnchor.label(1));
    }

    @Test
    public void label_atCap() {
        assertEquals("99 条新消息", ScrollAnchor.label(ScrollAnchor.MAX_UNREAD_LABEL));
    }

    @Test
    public void label_overCap_uses99Plus() {
        assertEquals("99+ 条新消息", ScrollAnchor.label(100));
        assertEquals("99+ 条新消息", ScrollAnchor.label(1000));
    }

    @Test
    public void label_negativeTreatedAsNone() {
        assertEquals("回到底部", ScrollAnchor.label(-1));
    }
}
