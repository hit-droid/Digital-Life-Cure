package com.digitallife.ui.shell;

import android.content.Context;
import android.content.Intent;
import android.view.View;

import com.digitallife.ui.ConversationTabView;
import com.digitallife.ui.ContactsTabView;
import com.digitallife.ui.DiscoverTabView;
import com.digitallife.ui.MainActivity;
import com.digitallife.ui.MemoryManageActivity;
import com.digitallife.ui.PluginTabView;
import com.digitallife.ui.SettingsTabView;
import com.digitallife.ui.UiKit;

/**
 * Operit 路由 → 内容视图工厂（v1.23.0 全量重构仿 Operit AI）。
 * 每个 route 返回一个可放入主内容区的 View。
 * 独立子页（记忆管理/护理大脑）走 Intent 跳转，不在主壳内。
 */
public class OperitContentView {

    public interface Host {
        ConversationTabView getConversationTab();
        ContactsTabView getContactsTab();
        DiscoverTabView getDiscoverTab();
        PluginTabView getPluginTab();
        SettingsTabView getSettingsTab();
        Context getContext();
    }

    public static View create(OperitRoute route, Host host) {
        Context ctx = host.getContext();
        switch (route) {
            case CHAT:
                ConversationTabView ct = host.getConversationTab();
                if (ct != null) ct.refresh();
                return ct;
            case CONTACTS:
                ContactsTabView cts = host.getContactsTab();
                if (cts != null) cts.refresh();
                return cts;
            case DISCOVER:
                DiscoverTabView dt = host.getDiscoverTab();
                if (dt != null) dt.refresh();
                return dt;
            case PLUGIN:
                return host.getPluginTab();
            case SETTINGS:
                SettingsTabView st = host.getSettingsTab();
                if (st != null) st.onResume();
                return st;
            case MEMORY:
                openActivity(ctx, MemoryManageActivity.class);
                return placeholder(ctx);
            default:
                return placeholder(ctx);
        }
    }

    private static void openActivity(Context ctx, Class<?> cls) {
        try {
            Intent i = new Intent(ctx, cls);
            if (!(ctx instanceof android.app.Activity)) {
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            }
            ctx.startActivity(i);
        } catch (Exception e) {
            UiKit.toast(ctx, "无法打开: " + UiKit.safeMsg(e));
        }
    }

    private static View placeholder(Context ctx) {
        android.widget.TextView tv = new android.widget.TextView(ctx);
        tv.setText("暂未实现");
        tv.setTextColor(0xFFB0B0B8);
        tv.setGravity(android.view.Gravity.CENTER);
        return tv;
    }
}
