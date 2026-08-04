package com.digitallife.service;

import android.accessibilityservice.AccessibilityService;
import android.content.Intent;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

/**
 * 无障碍感知服务（可选授权，不影响主功能）。
 * 感知当前前台应用与通知，让桌宠「知道」用户正在用什么。
 * 授权入口：MainActivity 引导跳转系统无障碍设置。
 */
public class PetAccessibilityService extends AccessibilityService {

    public interface Listener {
        /** 前台窗口变化（app 包名） */
        void onForegroundAppChanged(String packageName);
        /** 通知到来 */
        void onNotification(String packageName, String text);
    }

    private static PetAccessibilityService instance;
    private static Listener listener;

    private String currentPackage = "";

    public static boolean isConnected() { return instance != null; }

    public static String getForegroundPackage() {
        return instance != null ? instance.currentPackage : null;
    }

    public static void setListener(Listener l) {
        listener = l;
    }

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        instance = this;
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null || listener == null) return;
        switch (event.getEventType()) {
            case AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED: {
                String pkg = event.getPackageName() != null ? event.getPackageName().toString() : "";
                if (!pkg.isEmpty() && !pkg.equals(currentPackage)) {
                    currentPackage = pkg;
                    listener.onForegroundAppChanged(pkg);
                }
                break;
            }
            case AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED: {
                CharSequence text = event.getText() != null && !event.getText().isEmpty()
                        ? event.getText().get(0) : null;
                if (text != null) {
                    listener.onNotification(
                            event.getPackageName() != null ? event.getPackageName().toString() : "",
                            text.toString());
                }
                break;
            }
            default:
                break;
        }
    }

    @Override
    public void onInterrupt() {
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        instance = null;
    }

    public static String getEventDescription(AccessibilityEvent event) {
        if (event == null) return null;
        StringBuilder sb = new StringBuilder();
        if (event.getPackageName() != null) sb.append(event.getPackageName());
        if (event.getContentDescription() != null) sb.append(' ').append(event.getContentDescription());
        if (event.getText() != null && !event.getText().isEmpty()) sb.append(' ').append(event.getText().get(0));
        String s = sb.toString().trim();
        return s.isEmpty() ? null : s;
    }

    public static String getNodeText(AccessibilityNodeInfo node) {
        if (node == null) return null;
        CharSequence t = node.getText();
        if (t == null) t = node.getContentDescription();
        return t != null && t.length() > 0 ? t.toString() : null;
    }
}
