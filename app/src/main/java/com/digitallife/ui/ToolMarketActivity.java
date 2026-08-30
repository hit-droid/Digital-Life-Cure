package com.digitallife.ui;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.digitallife.R;
import com.digitallife.tools.ToolUsageLog;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * 工具市场：可视化所有可用工具的开关、使用次数、权限模型。
 * 借鉴 Operit AI 的工具市场设计。
 */
public class ToolMarketActivity extends Activity {

    private static final String PREF = "tool_market";
    private static final String KEY_DISABLED = "disabled_tools";
    private static final String KEY_ASK = "ask_tools";

    private LinearLayout listContainer;

    /** 工具元信息（展示用） */
    private static class ToolInfo {
        String name;
        String desc;
        String category;  // 表达/记忆/系统/实用/信息
        String icon;      // emoji
        ToolInfo(String n, String d, String c, String i) {
            name = n; desc = d; category = c; icon = i;
        }
    }

    private final List<ToolInfo> TOOLS = new ArrayList<ToolInfo>() {{
        // 表达
        add(new ToolInfo("set_expression", "设置角色的面部表情", "表达", "😊"));
        add(new ToolInfo("play_animation", "播放身体动作动画", "表达", "💃"));
        add(new ToolInfo("say", "让角色说一句话（气泡+语音）", "表达", "💬"));
        add(new ToolInfo("get_time", "获取当前时间", "表达", "🕐"));
        add(new ToolInfo("move", "让角色移动到指定位置", "表达", "📍"));
        // 记忆
        add(new ToolInfo("memory_search", "搜索过去记忆", "记忆", "🔍"));
        add(new ToolInfo("memory_recall", "主动回忆某个主题", "记忆", "💭"));
        add(new ToolInfo("memory_save", "保存重要记忆", "记忆", "📝"));
        add(new ToolInfo("memory_forget", "主动遗忘记忆", "记忆", "🗑️"));
        // 系统
        add(new ToolInfo("get_battery", "获取电量", "系统", "🔋"));
        add(new ToolInfo("get_location_hint", "获取大致位置", "系统", "🌍"));
        add(new ToolInfo("get_weather_hint", "获取天气提示", "系统", "☁️"));
        add(new ToolInfo("set_reminder", "设置提醒", "系统", "⏰"));
        add(new ToolInfo("take_screenshot_hint", "截图提示", "系统", "📸"));
        // 实用
        add(new ToolInfo("web_search", "联网搜索", "实用", "🌐"));
        add(new ToolInfo("open_app", "打开其他 App", "实用", "📱"));
        add(new ToolInfo("send_notification", "发送系统通知", "实用", "🔔"));
        add(new ToolInfo("clipboard_write", "写入剪贴板", "实用", "📋"));
        // 信息
        add(new ToolInfo("get_calendar_today", "今日日程", "信息", "📅"));
        add(new ToolInfo("get_contacts_hint", "联系人提示", "信息", "👥"));
        add(new ToolInfo("get_files_recent", "最近文件", "信息", "📁"));
    }};

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(buildUi());
    }

    private View buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(UiKit.color(this, R.color.operit_bg));

        // ===== 顶栏 =====
        LinearLayout topBar = new LinearLayout(this);
        topBar.setOrientation(LinearLayout.HORIZONTAL);
        topBar.setGravity(Gravity.CENTER_VERTICAL);
        topBar.setBackgroundResource(R.drawable.bg_operit_topbar);
        topBar.setPadding(UiKit.dp(this, 12), UiKit.dp(this, 12),
                UiKit.dp(this, 12), UiKit.dp(this, 12));
        topBar.setElevation(UiKit.dp(this, 4));

        Button btnBack = new Button(this);
        btnBack.setText("←");
        btnBack.setTextSize(20f);
        btnBack.setTextColor(Color.WHITE);
        btnBack.setBackgroundColor(Color.TRANSPARENT);
        btnBack.setAllCaps(false);
        btnBack.setOnClickListener(v -> finish());
        topBar.addView(btnBack, new LinearLayout.LayoutParams(
                UiKit.dp(this, 36), UiKit.dp(this, 36)));

        TextView title = new TextView(this);
        title.setText("工具市场");
        title.setTextSize(18f);
        title.setTextColor(Color.WHITE);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        tlp.leftMargin = UiKit.dp(this, 8);
        topBar.addView(title, tlp);

        // 今日调用统计
        ToolUsageLog log = new ToolUsageLog(this);
        TextView stat = new TextView(this);
        stat.setText("今日 " + log.countToday() + " 次");
        stat.setTextSize(12f);
        stat.setTextColor(Color.WHITE);
        topBar.addView(stat);

        root.addView(topBar);

        // ===== 总览卡 =====
        listContainer = new LinearLayout(this);
        listContainer.setOrientation(LinearLayout.VERTICAL);
        listContainer.setPadding(UiKit.dp(this, 12), UiKit.dp(this, 8),
                UiKit.dp(this, 12), UiKit.dp(this, 12));
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.addView(listContainer);
        root.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        renderList();
        return root;
    }

    /** 顶部统计卡：今日调用 + 已启用/总工具。 */
    private void renderSummary() {
        listContainer.removeAllViews();
        ToolUsageLog log = new ToolUsageLog(this);
        int enabled = 0;
        for (ToolInfo t : TOOLS) {
            if (!isDisabled(this, t.name)) enabled++;
        }
        int today = log.countToday();

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setBackgroundResource(R.drawable.bg_summary_card);
        card.setPadding(UiKit.dp(this, 16), UiKit.dp(this, 14),
                UiKit.dp(this, 16), UiKit.dp(this, 14));
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        clp.bottomMargin = UiKit.dp(this, 8);
        card.setLayoutParams(clp);

        card.addView(buildSummaryStat("今日调用", String.valueOf(today),
                UiKit.color(this, R.color.operit_accent)));
        card.addView(buildSummaryStat("已启用", enabled + "/" + TOOLS.size(),
                0xFF10B981));
        card.addView(buildSummaryStat("分类", "5",
                UiKit.color(this, R.color.brand_end)));
        listContainer.addView(card, 0);
    }

    private View buildSummaryStat(String label, String value, int valueColor) {
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        col.setLayoutParams(lp);
        TextView v = new TextView(this);
        v.setText(value);
        v.setTextSize(20f);
        v.setTextColor(valueColor);
        v.setTypeface(Typeface.DEFAULT_BOLD);
        col.addView(v);
        TextView l = new TextView(this);
        l.setText(label);
        l.setTextSize(11f);
        l.setTextColor(UiKit.color(this, R.color.operit_text_hint));
        col.addView(l);
        return col;
    }

    private void renderList() {
        listContainer.removeAllViews();
        String currentCategory = null;
        for (ToolInfo t : TOOLS) {
            if (!t.category.equals(currentCategory)) {
                currentCategory = t.category;
                listContainer.addView(buildCategoryHeader(currentCategory));
            }
            listContainer.addView(buildToolRow(t));
        }
    }

    private View buildCategoryHeader(String cat) {
        TextView tv = new TextView(this);
        tv.setText("  " + cat + "  ");
        tv.setTextSize(12f);
        tv.setTextColor(Color.WHITE);
        tv.setTypeface(Typeface.DEFAULT_BOLD);
        tv.setBackgroundResource(R.drawable.bg_category_badge);
        tv.setPadding(UiKit.dp(this, 10), UiKit.dp(this, 4),
                UiKit.dp(this, 10), UiKit.dp(this, 4));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = UiKit.dp(this, 14);
        lp.bottomMargin = UiKit.dp(this, 6);
        tv.setLayoutParams(lp);
        return tv;
    }

    private View buildToolRow(ToolInfo t) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setBackgroundResource(R.drawable.bg_card);
        row.setPadding(UiKit.dp(this, 12), UiKit.dp(this, 10),
                UiKit.dp(this, 12), UiKit.dp(this, 10));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = UiKit.dp(this, 6);
        row.setLayoutParams(lp);

        // 图标
        TextView icon = new TextView(this);
        icon.setText(t.icon);
        icon.setTextSize(24f);
        row.addView(icon, new LinearLayout.LayoutParams(
                UiKit.dp(this, 36), ViewGroup.LayoutParams.WRAP_CONTENT));

        // 名称 + 描述
        LinearLayout info = new LinearLayout(this);
        info.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        ilp.leftMargin = UiKit.dp(this, 12);
        TextView name = new TextView(this);
        name.setText(t.name);
        name.setTextSize(14f);
        name.setTextColor(UiKit.color(this, R.color.operit_text_primary));
        name.setTypeface(Typeface.DEFAULT_BOLD);
        info.addView(name);
        TextView desc = new TextView(this);
        desc.setText(t.desc);
        desc.setTextSize(12f);
        desc.setTextColor(UiKit.color(this, R.color.operit_text_secondary));
        info.addView(desc);
        row.addView(info, ilp);

        // 开关
        boolean enabled = !isDisabled(this, t.name);
        Button btnToggle = new Button(this);
        btnToggle.setText(enabled ? "已启用" : "已禁用");
        btnToggle.setTextSize(12f);
        btnToggle.setTextColor(Color.WHITE);
        btnToggle.setAllCaps(false);
        btnToggle.setBackgroundResource(enabled
                ? R.drawable.bg_btn_primary : R.drawable.bg_btn_secondary);
        btnToggle.setPadding(UiKit.dp(this, 10), 0,
                UiKit.dp(this, 10), 0);
        btnToggle.setOnClickListener(v -> {
            boolean nowEnabled = isDisabled(this, t.name);
            setDisabled(this, t.name, nowEnabled);
            btnToggle.setText(nowEnabled ? "已启用" : "已禁用");
            btnToggle.setBackgroundResource(nowEnabled
                    ? R.drawable.bg_btn_primary : R.drawable.bg_btn_secondary);
        });
        row.addView(btnToggle, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, UiKit.dp(this, 32)));
        return row;
    }

    // ============= 持久化：禁用/启用 =============

    public static boolean isDisabled(Context ctx, String toolName) {
        SharedPreferences sp = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE);
        return sp.getString(KEY_DISABLED, "").contains(toolName);
    }

    public static void setDisabled(Context ctx, String toolName, boolean disabled) {
        SharedPreferences sp = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE);
        java.util.Set<String> set = new java.util.HashSet<>(
                java.util.Arrays.asList(sp.getString(KEY_DISABLED, "").split(",")));
        set.remove("");
        if (disabled) set.add(toolName);
        else set.remove(toolName);
        sp.edit().putString(KEY_DISABLED,
                android.text.TextUtils.join(",", set)).apply();
    }

    public static java.util.Set<String> getDisabled(Context ctx) {
        SharedPreferences sp = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE);
        java.util.Set<String> set = new java.util.HashSet<>(
                java.util.Arrays.asList(sp.getString(KEY_DISABLED, "").split(",")));
        set.remove("");
        return set;
    }
}
