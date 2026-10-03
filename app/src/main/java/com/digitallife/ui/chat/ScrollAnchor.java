package com.digitallife.ui.chat;

/**
 * 聊天列表「回到底部」锚点（Issue #43，纯逻辑，JVM 可测）。
 *
 * <p>{@code ChatActivity} 原先只有「用户上翻就不再自动滚底」——用户离开底部后
 * 没有任何回去的入口，期间到达的新消息也毫无提示。这里把两块判定抽出来：</p>
 * <ul>
 *   <li>是否该显示按钮：底部间隙 {@code bottomGapPx} 超过阈值；</li>
 *   <li>未读累积 / 清零，以及按钮文案（含 99+ 上限）。</li>
 * </ul>
 *
 * <p>不碰 Android API，UI 只负责把结果画出来。</p>
 */
public final class ScrollAnchor {

    /** 未读计数在按钮上的显示上限，超过按 99+ 计 */
    public static final int MAX_UNREAD_LABEL = 99;

    private int unread = 0;

    /** 底部间隙超过阈值即认为「已离开底部」，应显示回到底部按钮 */
    public static boolean shouldShow(float bottomGapPx, int thresholdPx) {
        return bottomGapPx > thresholdPx;
    }

    /**
     * 追加了一条消息。
     *
     * @param atBottom 追加时用户是否正停在底部；停在底部时无需累积未读
     */
    public void onMessageAppended(boolean atBottom) {
        if (!atBottom) unread++;
    }

    /** 当前未读数 */
    public int unreadCount() {
        return unread;
    }

    /** 回到（或图片/布局把用户带回）底部时清零 */
    public void clear() {
        unread = 0;
    }

    /** 按钮文案：无未读是「回到底部」，有未读标条数，99 以上显示 99+ */
    public static String label(int unread) {
        if (unread <= 0) return "回到底部";
        int shown = Math.min(unread, MAX_UNREAD_LABEL);
        return shown + (unread > MAX_UNREAD_LABEL ? "+ " : " ") + "条新消息";
    }
}
