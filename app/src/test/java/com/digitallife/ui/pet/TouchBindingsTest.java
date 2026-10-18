package com.digitallife.ui.pet;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;
import java.util.List;

/**
 * 事件绑定纯逻辑单测（v1.176.0）。锁三件事：①「绑定优先、空回落默认、哨兵 NONE 明确不绑」
 * 的生效值解析；②可绑定事件集合与各自默认；③候选名去重剔除空串。不碰存储。
 */
public class TouchBindingsTest {

    @Test
    public void effective_boundWinsOverFallback() {
        assertEquals("F01", TouchBindings.effective("F01", "F06"));
        assertEquals("Shy", TouchBindings.effective("Shy", "TapBody"));
    }

    @Test
    public void effective_emptyOrNullFallsBackToDefault() {
        assertEquals("F06", TouchBindings.effective("", "F06"));
        assertEquals("F06", TouchBindings.effective(null, "F06"));
        // 默认也没有 → null
        assertNull(TouchBindings.effective("", null));
        assertNull(TouchBindings.effective("", ""));
        assertNull(TouchBindings.effective(null, null));
    }

    @Test
    public void effective_noneSentinelMeansNoBinding() {
        assertNull(TouchBindings.effective(TouchBindings.NONE, "F06"));
        assertNull(TouchBindings.effective(TouchBindings.NONE, null));
    }

    @Test
    public void events_exposesFourWithDefaults() {
        TouchBindings.Event[] events = TouchBindings.events();
        assertEquals(4, events.length);

        TouchBindings.Event head = TouchBindings.eventFor(TouchBindings.EVENT_HEAD);
        assertEquals("摸头", head.label);
        assertEquals("F06", head.defaultExpression);
        assertEquals("Shy", head.defaultMotionGroup);

        TouchBindings.Event body = TouchBindings.eventFor(TouchBindings.EVENT_BODY);
        assertEquals("F05", body.defaultExpression);
        assertEquals("TapBody", body.defaultMotionGroup);

        TouchBindings.Event dbl = TouchBindings.eventFor(TouchBindings.EVENT_DOUBLE_TAP);
        assertEquals("F02", dbl.defaultExpression);
        assertNull(dbl.defaultMotionGroup);

        TouchBindings.Event none = TouchBindings.eventFor(TouchBindings.EVENT_NONE);
        assertNull(none.defaultExpression);
        assertEquals("TapBody", none.defaultMotionGroup);

        assertNull(TouchBindings.eventFor("NoSuchEvent"));
        assertNull(TouchBindings.eventFor(null));
    }

    @Test
    public void events_returnsDefensiveCopy() {
        TouchBindings.Event[] a = TouchBindings.events();
        a[0] = null;
        // 内部表不受影响
        assertTrue(TouchBindings.events()[0] != null);
    }

    @Test
    public void distinctNames_dedupesAndDropsBlanks() {
        List<String> out = TouchBindings.distinctNames(
                Arrays.asList("F01", "F02", "F01", null, "", "F02", "Shy"));
        assertEquals(3, out.size());
        assertEquals("F01", out.get(0));
        assertEquals("F02", out.get(1));
        assertEquals("Shy", out.get(2));
        assertTrue(TouchBindings.distinctNames(null).isEmpty());
    }
}
