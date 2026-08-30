package com.digitallife.ui.shell;

import android.content.Context;
import android.content.Intent;
import android.view.View;
import android.view.ViewGroup;

import com.digitallife.ui.AboutActivity;
import com.digitallife.ui.ConversationTabView;
import com.digitallife.ui.ContactsTabView;
import com.digitallife.ui.DeveloperActivity;
import com.digitallife.ui.DiscoverTabView;
import com.digitallife.ui.MemoryManageActivity;
import com.digitallife.ui.PluginTabView;
import com.digitallife.ui.SettingsTabView;
import com.digitallife.ui.ThemesActivity;
import com.digitallife.ui.UiKit;

import java.util.EnumMap;
import java.util.Map;

/**
 * Operit 路由 → 内容视图工厂（v1.23.0 全量重构仿 Operit AI）。
 * 缓存主壳 5 个 Tab 的 View 实例以保留滚动/输入状态。
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
        android.app.Activity getActivity();
    }

    private final Map<OperitRoute, View> cache = new EnumMap<>(OperitRoute.class);

    public View obtain(OperitRoute route, Host host) {
        if (cache.containsKey(route)) return cache.get(route);
        View v = create(route, host);
        if (v != null) cache.put(route, v);
        return v;
    }

    private View create(OperitRoute route, Host host) {
        Context ctx = host.getContext();
        switch (route) {
            case CHAT: {
                ConversationTabView v = host.getConversationTab();
                if (v != null) v.refresh();
                return v;
            }
            case CONTACTS: {
                ContactsTabView v = host.getContactsTab();
                if (v != null) v.refresh();
                return v;
            }
            case DISCOVER: {
                DiscoverTabView v = host.getDiscoverTab();
                if (v != null) v.refresh();
                return v;
            }
            case PLUGIN: {
                return host.getPluginTab();
            }
            case SETTINGS: {
                SettingsTabView v = host.getSettingsTab();
                if (v != null) v.onResume();
                return v;
            }
            case MEMORY:
                openActivity(ctx, MemoryManageActivity.class);
                return null;
            case CARE:
                openActivity(ctx, com.digitallife.care.CareModelsActivity.class);
                return null;
            case TOOLMARKET:
                openActivity(ctx, com.digitallife.ui.ToolMarketActivity.class);
                return null;
            case THEMES:
                openActivity(ctx, ThemesActivity.class);
                return null;
            case DEVELOPER:
                openActivity(ctx, DeveloperActivity.class);
                return null;
            case ABOUT:
                openActivity(ctx, AboutActivity.class);
                return null;
            default:
                return null;
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
            android.widget.Toast.makeText(ctx, "无法打开: " + e.getMessage(),
                    android.widget.Toast.LENGTH_SHORT).show();
        }
    }
}
