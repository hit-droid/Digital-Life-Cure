package com.digitallife.util;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * {@link AutoStartPolicy} 单测（v1.133.0）：开机自启的真值表。
 */
public class AutoStartPolicyTest {

    @Test
    public void startsOnlyWhenAllThreeHold() {
        assertTrue(AutoStartPolicy.shouldStart(true, true, true));
    }

    @Test
    public void disabledByAutoStartSwitch() {
        // 用户在设置里关掉自启
        assertFalse(AutoStartPolicy.shouldStart(false, true, true));
    }

    @Test
    public void disabledWhenPetWasNotRunning() {
        // 从来没用过桌宠的用户，重启不该被莫名拉起
        assertFalse(AutoStartPolicy.shouldStart(true, false, true));
    }

    @Test
    public void disabledWithoutOverlayPermission() {
        // 悬浮窗权限被撤销后拉起来也显示不了，不如不拉
        assertFalse(AutoStartPolicy.shouldStart(true, true, false));
    }

    @Test
    public void disabledWhenNothingHolds() {
        assertFalse(AutoStartPolicy.shouldStart(false, false, false));
    }
}
