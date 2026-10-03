package com.digitallife.ui.chat;

/**
 * 聊天页「回到底部」浮标的纯逻辑部分（issue #43）。
 *
 * <p>背景：ChatActivity 早就有「用户上翻就暂停自动滚底」的判定，但上翻之后
 * 没有任何回去的入口——新消息还在追加，用户只能自己滑回去，期间到了几条
 * 回复也完全没提示。这里把「要不要显示浮标」「累计了几条未读」「按钮上写什么」
 * 三件事抽成不依赖 Android 的纯逻辑，好钉在 JVM 单测里；UI 只负责展示。</p>
 *
 * <p>只描述状态，不碰 View、不碰持久化、不改流式回调语义。</p>
 */
public final class ScrollAnchor {

    /** 未读条数的显示上限，超过就显示 {@code 99+} */
    static final int MAX_LABEL_COUNT = 99;

    private int unread;

    /** 当前累计的未读条数 */
    public int unreadCount() {
        return unread;
    }

    /** 清零（回到底部 / 用户点过浮标之后） */
    public void clear() {
        unread = 0;
    }

    /**
     * 追加了一条消息。
     *
     * @param atBottom 追加时用户是否正停在底部：看得见就不算未读（顺带把旧计数清掉），
     *                 看不见才累加。
     */
    public void onMessageAppended(boolean atBottom) {
        if (atBottom) {
            unread = 0;
        } else {
            unread++;
        }
    }

    /**
     * 是否该显示浮标：列表底部距可视底部的间隙超过阈值（用户已经上翻离开了底部）。
     *
     * <p>取严格大于：间隙正好等于阈值时算「还在底部」，避免出现和隐藏反复抖动。</p>
     */
    public static boolean shouldShow(float bottomGapPx, int thresholdPx) {
        return bottomGapPx > thresholdPx;
    }

    /**
     * 浮标文案：没有未读是「回到底部」，有未读是「N 条新消息」，超过上限显示 {@code 99+}。
     */
    public static String label(int unread) {
        if (unread <= 0) return "回到底部";
        if (unread > MAX_LABEL_COUNT) return MAX_LABEL_COUNT + "+ 条新消息";
        return unread + " 条新消息";
    }
}
