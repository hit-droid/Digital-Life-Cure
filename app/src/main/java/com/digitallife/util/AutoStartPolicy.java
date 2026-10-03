package com.digitallife.util;

/**
 * 桌宠「开机自启」判定纯逻辑（v1.133.0）。
 *
 * <p>开机时是否拉起桌宠，取决于三件事同时成立：用户开了自启、关机前桌宠确实在跑、
 * 以及悬浮窗权限还在。这里只做布尔判定，不碰 Android API，便于 JVM 单测。
 */
public final class AutoStartPolicy {

    private AutoStartPolicy() {
    }

    /**
     * @param autoStartEnabled 用户设置里的「开机自动启动」
     * @param petWasRunning    关机前桌宠是否处于运行状态（用户意图，非进程存活）
     * @param overlayGranted   是否仍持有悬浮窗权限
     * @return 三个条件都满足才返回 true，避免没启用过的用户被莫名启动
     */
    public static boolean shouldStart(boolean autoStartEnabled, boolean petWasRunning, boolean overlayGranted) {
        return autoStartEnabled && petWasRunning && overlayGranted;
    }
}
