package com.digitallife.ui.shell;

import com.digitallife.R;

/**
 * Operit 路由枚举（v1.23.0 全量重构仿 Operit AI）。
 * 每个 Route 对应一个左侧导航菜单项 + 右侧内容视图。
 */
public enum OperitRoute {
    CHAT       (R.string.route_chat,       R.drawable.ic_tab_chat,     "chat",       true,  "和她说说话，聊聊今天"),
    CONTACTS   (R.string.route_contacts,   R.drawable.ic_tab_contacts, "contacts",   true,  "每个模型都有自己的小房间"),
    DISCOVER   (R.string.route_discover,   R.drawable.ic_tab_discover, "discover",   true,  "她的内心世界与记忆"),
    PLUGIN     (R.string.route_plugin,     R.drawable.ic_tab_plugin,   "plugin",     true,  "扩展工具，让她更强大"),
    SETTINGS   (R.string.route_settings,   R.drawable.ic_tab_settings, "settings",   true,  "配置你的数字生命"),

    MEMORY     (R.string.route_memory,     R.drawable.ic_tab_discover, "memory",     false, "记忆图谱化管理"),
    CARE       (R.string.route_care,       R.drawable.ic_tab_discover, "care",       false, "护理大脑，管理模型"),
    PERSONA    (R.string.route_persona,    R.drawable.ic_tab_settings, "persona",    false, "创建/编辑/切换角色"),
    TOOLMARKET (R.string.route_tool_market, R.drawable.ic_tab_tool,    "tool",       false, "启用/禁用工具，查看调用统计"),
    THEMES     (R.string.route_themes,     R.drawable.ic_tab_settings, "themes",     false, "自定义主题风格"),
    DEVELOPER  (R.string.route_developer,  R.drawable.ic_tab_settings, "developer",  false, "开发者调试工具"),
    ABOUT      (R.string.route_about,      R.drawable.ic_tab_settings, "about",      false, "版本与开源许可");

    public final int titleRes;
    public final int iconRes;
    public final String id;
    public final boolean primary;
    public final String subtitle;

    OperitRoute(int titleRes, int iconRes, String id, boolean primary, String subtitle) {
        this.titleRes = titleRes;
        this.iconRes = iconRes;
        this.id = id;
        this.primary = primary;
        this.subtitle = subtitle;
    }
}
