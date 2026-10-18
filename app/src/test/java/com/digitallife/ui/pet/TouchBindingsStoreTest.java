package com.digitallife.ui.pet;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

/**
 * 事件绑定持久化单测（v1.176.0）。锁「写读往返」「空串=清掉回到默认」「哨兵 NONE 落盘且不算默认」
 * 「恢复默认清两项」「事件之间互不串」几个契约。
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 33)
public class TouchBindingsStoreTest {

    private Context ctx() {
        return RuntimeEnvironment.getApplication();
    }

    @Before
    public void reset() {
        ctx().getSharedPreferences("care_touch_bindings", Context.MODE_PRIVATE).edit().clear().commit();
    }

    @Test
    public void unset_isDefaultAndEmpty() {
        TouchBindings.Binding b = TouchBindings.get(ctx(), TouchBindings.EVENT_HEAD);
        assertTrue(b.isEmpty());
        assertEquals("", b.expression);
        assertEquals("", b.motionGroup);
        assertTrue(TouchBindings.isDefault(ctx(), TouchBindings.EVENT_HEAD));
    }

    @Test
    public void setExpression_roundTrips() {
        TouchBindings.setExpression(ctx(), TouchBindings.EVENT_HEAD, "F01");
        assertEquals("F01", TouchBindings.get(ctx(), TouchBindings.EVENT_HEAD).expression);
        assertFalse(TouchBindings.isDefault(ctx(), TouchBindings.EVENT_HEAD));
    }

    @Test
    public void setMotionGroup_roundTrips() {
        TouchBindings.setMotionGroup(ctx(), TouchBindings.EVENT_BODY, "Idle");
        assertEquals("Idle", TouchBindings.get(ctx(), TouchBindings.EVENT_BODY).motionGroup);
    }

    @Test
    public void emptyValue_clearsBackToDefault() {
        TouchBindings.setExpression(ctx(), TouchBindings.EVENT_HEAD, "F01");
        TouchBindings.setExpression(ctx(), TouchBindings.EVENT_HEAD, "");
        assertEquals("", TouchBindings.get(ctx(), TouchBindings.EVENT_HEAD).expression);
    }

    @Test
    public void noneSentinel_persistsAndCountsAsSet() {
        TouchBindings.setMotionGroup(ctx(), TouchBindings.EVENT_BODY, TouchBindings.NONE);
        assertEquals(TouchBindings.NONE,
                TouchBindings.get(ctx(), TouchBindings.EVENT_BODY).motionGroup);
        assertFalse(TouchBindings.isDefault(ctx(), TouchBindings.EVENT_BODY));
    }

    @Test
    public void clear_resetsBothKeys() {
        TouchBindings.setExpression(ctx(), TouchBindings.EVENT_DOUBLE_TAP, "F05");
        TouchBindings.setMotionGroup(ctx(), TouchBindings.EVENT_DOUBLE_TAP, "Tap");
        assertFalse(TouchBindings.isDefault(ctx(), TouchBindings.EVENT_DOUBLE_TAP));
        TouchBindings.clear(ctx(), TouchBindings.EVENT_DOUBLE_TAP);
        assertTrue(TouchBindings.get(ctx(), TouchBindings.EVENT_DOUBLE_TAP).isEmpty());
        assertTrue(TouchBindings.isDefault(ctx(), TouchBindings.EVENT_DOUBLE_TAP));
    }

    @Test
    public void events_areIsolated() {
        TouchBindings.setExpression(ctx(), TouchBindings.EVENT_HEAD, "F01");
        TouchBindings.setMotionGroup(ctx(), TouchBindings.EVENT_BODY, "Idle");
        assertEquals("F01", TouchBindings.get(ctx(), TouchBindings.EVENT_HEAD).expression);
        assertEquals("", TouchBindings.get(ctx(), TouchBindings.EVENT_HEAD).motionGroup);
        assertEquals("", TouchBindings.get(ctx(), TouchBindings.EVENT_BODY).expression);
        assertEquals("Idle", TouchBindings.get(ctx(), TouchBindings.EVENT_BODY).motionGroup);
        assertTrue(TouchBindings.isDefault(ctx(), TouchBindings.EVENT_NONE));
    }
}
