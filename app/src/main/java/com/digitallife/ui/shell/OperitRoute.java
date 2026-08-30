package com.digitallife.ui.shell;

import com.digitallife.R;

/**
 * Operit 路由枚举（v1.23.0 全量重构仿 Operit AI）。
 * 每个 Route 对应一个左侧导航菜单项 + 右侧内容视图。
 */
public enum OperitRoute {
    CHAT       (R.string.route_chat,       R.drawable.ic_tab_chat,     "chat",       true),
    CONTACTS   (R.string.route_contacts,   R.drawable.ic_tab_contacts, "contacts",   true),
    DISCOVER   (R.string.route_discover,   R.drawable.ic_tab_discover, "discover",   true),
    PLUGIN     (R.string.route_plugin,     R.drawable.ic_tab_plugin,   "plugin",     true),
    SETTINGS   (R.string.route_settings,   R.drawable.ic_tab_settings, "settings",   true),

    MEMORY     (R.string.route_memory,     R.drawable.ic_tab_discover, "memory",     false),
    CARE       (R.string.route_care,       R.drawable.ic_tab_discover, "care",       false),
    THEMES     (R.string.route_themes,     R.drawable.ic_tab_settings, "themes",     false),
    DEVELOPER  (R.string.route_developer,  R.drawable.ic_tab_settings, "developer",  false),
    ABOUT      (R.string.route_about,      R.drawable.ic_tab_settings, "about",      false);

    public final int titleRes;
    public final int iconRes;
    public final String id;
    public final boolean primary;

    OperitRoute(int titleRes, int iconRes, String id, boolean primary) {
        this.titleRes = titleRes;
        this.iconRes = iconRes;
        this.id = id;
        this.primary = primary;
    }
}
