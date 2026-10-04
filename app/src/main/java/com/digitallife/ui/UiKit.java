package com.digitallife.ui;

import android.app.Activity;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.Switch;
import android.widget.TextView;

import com.digitallife.R;

/**
 * 微信式 UI 共享构建工具：卡片/输入框/按钮/开关等统一样式。
 */
public final class UiKit {

    private UiKit() {
    }

    /** 异常消息兜底：getMessage() 为 null 时回退为异常类名，避免界面直显 "null" */
    public static String safeMsg(Throwable t) {
        if (t == null) return "未知错误";
        String m = t.getMessage();
        if (m == null || m.trim().isEmpty()) {
            return t.getClass().getSimpleName();
        }
        return m;
    }

    public static int dp(Context c, float v) {
        return Math.round(c.getResources().getDisplayMetrics().density * v);
    }

    public static int color(Context c, int res) {
        return c.getResources().getColor(res);
    }

    /** 读取真实 versionName，避免界面里写死版本号而随发版失真 */
    public static String appVersion(Context c) {
        try {
            String v = c.getPackageManager().getPackageInfo(c.getPackageName(), 0).versionName;
            return v == null ? "?" : v;
        } catch (Exception e) {
            return "?";
        }
    }

    /** 系统状态栏高度（取不到时返回 0） */
    public static int statusBarHeight(Context c) {
        int id = c.getResources().getIdentifier("status_bar_height", "dimen", "android");
        return id > 0 ? c.getResources().getDimensionPixelSize(id) : 0;
    }

    /**
     * 二级页面统一顶栏：Operit 紫色渐隐 + 圆形返回按钮 + 标题，自动留出状态栏高度。
     * <p>此前各二级页各写一份顶栏，且部分页面漏了状态栏内边距，标题会被状态栏压住。</p>
     */
    public static LinearLayout pageTopBar(Activity a, String title) {
        LinearLayout bar = new LinearLayout(a);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setBackgroundResource(R.drawable.bg_operit_topbar);
        bar.setPadding(dp(a, 8), dp(a, 10) + statusBarHeight(a), dp(a, 14), dp(a, 10));

        ImageView back = new ImageView(a);
        back.setImageResource(R.drawable.ic_back);
        back.setColorFilter(Color.WHITE);
        back.setScaleType(ImageView.ScaleType.CENTER);
        back.setContentDescription("返回");
        back.setOnClickListener(v -> a.finish());
        GradientDrawable circle = new GradientDrawable();
        circle.setShape(GradientDrawable.OVAL);
        circle.setColor(0x33FFFFFF);
        ripple(a, back, circle, 18);
        bar.addView(back, new LinearLayout.LayoutParams(dp(a, 36), dp(a, 36)));

        TextView t = new TextView(a);
        t.setText(title);
        t.setTextSize(18f);
        t.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        t.setTextColor(Color.WHITE);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        tlp.leftMargin = dp(a, 10);
        bar.addView(t, tlp);
        return bar;
    }

    public static LinearLayout.LayoutParams lp(Context c, int extra) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        if (extra > 0) p.topMargin = dp(c, extra);
        return p;
    }

    /**
     * 内容卡片容器。
     * <p>统一到 Operit 语言：标题移到卡片外部（强调色小节标题），卡片本身是圆角 surface 面板。
     * 返回值仍是「可继续 addView 的容器」，对旧调用方保持兼容。</p>
     */
    public static LinearLayout card(Context c, LinearLayout root, String title) {
        if (title != null && !title.isEmpty()) {
            sectionTitle(c, root, title, null);
        }
        return panel(c, root);
    }

    /** Operit 风格圆角内容面板（自身不含标题），配合 {@link #sectionTitle} 使用 */
    public static LinearLayout panel(Context c, LinearLayout root) {
        LinearLayout box = new LinearLayout(c);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(c, 14), dp(c, 14), dp(c, 14), dp(c, 14));
        box.setBackgroundResource(R.drawable.bg_card);
        root.addView(box, lp(c, 0));
        return box;
    }

    /** Operit 风格小号 chip 操作按钮（描边胶囊），用于「卸载 / 删除 / 复制」等次级操作 */
    public static TextView chipButton(Context c, LinearLayout root, String text, int colorRes) {
        TextView tv = new TextView(c);
        tv.setText(text);
        tv.setTextSize(12f);
        tv.setTextColor(color(c, colorRes));
        tv.setBackgroundResource(R.drawable.bg_chip_outline);
        tv.setGravity(Gravity.CENTER);
        tv.setPadding(dp(c, 12), dp(c, 6), dp(c, 12), dp(c, 6));
        tv.setIncludeFontPadding(false);
        pressScale(tv);
        root.addView(tv);
        return tv;
    }

    public static EditText input(Context c, LinearLayout root, String hint, String value) {
        EditText et = new EditText(c);
        et.setHint(hint);
        et.setHintTextColor(color(c, R.color.operit_text_hint));
        if (value != null) et.setText(value);
        et.setTextSize(14f);
        et.setTextColor(color(c, R.color.operit_text_primary));
        et.setBackgroundResource(R.drawable.bg_input);
        et.setPadding(dp(c, 12), 0, dp(c, 12), 0);
        root.addView(et, lp(c, 8));
        LinearLayout.LayoutParams elp = (LinearLayout.LayoutParams) et.getLayoutParams();
        elp.height = dp(c, 44);
        return et;
    }

    public static Button button(Context c, LinearLayout root, String text) {
        Button b = new Button(c);
        b.setHapticFeedbackEnabled(true);   // 自动生成：haptic
        b.setContentDescription("b");   // 自动生成：a11y
        b.setText(text);
        b.setTextSize(14f);
        b.setTextColor(Color.WHITE);
        b.setBackgroundResource(R.drawable.bg_btn_primary);
        b.setAllCaps(false);
        b.setPadding(dp(c, 14), 0, dp(c, 14), 0);
        pressScale(b);
        root.addView(b, lp(c, 8));
        LinearLayout.LayoutParams blp = (LinearLayout.LayoutParams) b.getLayoutParams();
        blp.height = dp(c, 44);
        return b;
    }

    /** 次级按钮（浅色底） */
    public static Button secondaryButton(Context c, LinearLayout root, String text) {
        Button b = new Button(c);
        b.setText(text);
        b.setTextSize(13f);
        b.setTextColor(color(c, R.color.brand));
        b.setBackgroundResource(R.drawable.bg_btn_secondary);
        b.setAllCaps(false);
        b.setPadding(dp(c, 14), 0, dp(c, 14), 0);
        pressScale(b);
        root.addView(b, lp(c, 8));
        LinearLayout.LayoutParams slp = (LinearLayout.LayoutParams) b.getLayoutParams();
        slp.height = dp(c, 42);
        return b;
    }

    /** 按下缩放反馈（不消费点击事件） */
    public static void pressScale(View v) {
        v.setOnTouchListener((view, event) -> {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    view.animate().scaleX(0.96f).scaleY(0.96f).setDuration(90).start();
                    break;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    view.animate().scaleX(1f).scaleY(1f).setDuration(140).start();
                    break;
            }
            return false;
        });
    }

    /**
     * 触发一次"点击闪光"反馈（短促缩放回弹）。
     * 必须在 View.setOnClickListener 之后调用，作为一次性动画。
     */
    public static void flash(View v) {
        v.animate().scaleX(0.92f).scaleY(0.92f).setDuration(70)
                .withEndAction(() ->
                        v.animate().scaleX(1f).scaleY(1f).setDuration(180).start())
                .start();
    }

    /** 给可点击容器套品牌色涟漪反馈（content 背景 + 圆角 mask） */
    public static void ripple(Context c, View v, Drawable content, float radiusDp) {
        GradientDrawable mask = new GradientDrawable();
        mask.setCornerRadius(dp(c, radiusDp));
        mask.setColor(0xFF000000);
        android.graphics.drawable.RippleDrawable rd = new android.graphics.drawable.RippleDrawable(
                ColorStateList.valueOf(color(c, R.color.ripple)), content, mask);
        v.setBackground(rd);
        v.setClickable(true);
    }

    public static Switch switchRow(Context c, LinearLayout root, String label, boolean checked) {
        LinearLayout row = new LinearLayout(c);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        TextView t = new TextView(c);
        t.setText(label);
        t.setTextSize(14f);
        t.setTextColor(color(c, R.color.operit_text_primary));
        row.addView(t, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        Switch sw = new Switch(c);
        sw.setChecked(checked);
        row.addView(sw);
        root.addView(row, lp(c, 8));
        return sw;
    }

    /** 将常见 HTTP 错误转换为可操作的排查提示 */
    public static String friendlyApiError(String err, String base, String model) {
        String lower = err == null ? "" : err.toLowerCase(java.util.Locale.ROOT);
        String hint;
        if (lower.contains("400")) {
            hint = "提示：HTTP 400 通常是「模型名不支持」或「请求参数不被服务端接受」。\n"
                    + "1) 确认模型名「" + model + "」在 " + base + " 上存在；"
                    + "下拉列表里是常用模型，可点选后重试。\n"
                    + "2) 若服务端是本地/自建网关，确认它实现的是 OpenAI 兼容 /chat/completions 接口。\n"
                    + "3) 部分服务需在请求体带 temperature 等参数，或改用流式(stream=true)再试。";
        } else if (lower.contains("401") || lower.contains("403")) {
            hint = "提示：HTTP 401/403 通常是 API Key 无效或无权限，请检查 Key 是否正确、余额是否充足。";
        } else if (lower.contains("404")) {
            hint = "提示：HTTP 404 通常是 Base URL 路径不对。确认地址以 /v1 结尾（如 https://api.deepseek.com/v1），且服务支持 /chat/completions。";
        } else if (lower.contains("timeout") || lower.contains("connect")) {
            hint = "提示：连接超时。确认 Base URL 可访问、网络畅通；若在国内环境访问海外服务，可能需要可达的镜像地址。";
        } else {
            hint = "提示：请检查 Base URL、API Key、模型名三项是否都正确，必要时清空对话记忆后重试。";
        }
        return hint;
    }

    public static LinearLayout expandableCard(Context c, LinearLayout root, String title, boolean defaultExpanded) {
        LinearLayout group = new LinearLayout(c);
        group.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams glp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        glp.topMargin = dp(c, 18);
        root.addView(group, glp);

        LinearLayout header = new LinearLayout(c);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(0, dp(c, 4), 0, dp(c, 4));
        header.setClickable(true);

        TextView t = new TextView(c);
        t.setText(title);
        t.setTextSize(15f);
        t.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        t.setLetterSpacing(0.04f);
        t.setTextColor(color(c, R.color.operit_accent));
        header.addView(t, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));

        TextView arrow = new TextView(c);
        arrow.setText(defaultExpanded ? "▾" : "▸");
        arrow.setTextSize(14f);
        arrow.setTextColor(color(c, R.color.operit_text_secondary));
        header.addView(arrow, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        group.addView(header, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout content = new LinearLayout(c);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setVisibility(defaultExpanded ? View.VISIBLE : View.GONE);
        group.addView(content, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        header.setOnClickListener(v -> {
            boolean visible = content.getVisibility() == View.VISIBLE;
            content.setVisibility(visible ? View.GONE : View.VISIBLE);
            arrow.setText(visible ? "▸" : "▾");
        });

        return content;
    }

    // v1.23.0: Operit 风格工具（深色紫色卡片列表）

    /**
     * 弹 Operit 风格「关于」对话框：紫色头部 + 卡片式信息列表 + 关闭按钮。
     * 复用：侧栏「关于」菜单项。
     */
    public static void aboutDialog(Activity activity) {
        android.app.AlertDialog.Builder b = new android.app.AlertDialog.Builder(activity);
        LinearLayout wrap = new LinearLayout(activity);
        wrap.setOrientation(LinearLayout.VERTICAL);
        wrap.setBackgroundColor(color(activity, R.color.operit_bg));
        int pad = dp(activity, 20);
        wrap.setPadding(pad, pad, pad, pad / 2);

        TextView title = new TextView(activity);
        title.setText("数字生命");
        title.setTextSize(20f);
        title.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        title.setTextColor(color(activity, R.color.operit_accent));
        wrap.addView(title);

        TextView subtitle = new TextView(activity);
        subtitle.setText("AI 桌宠 · 仿 Operit AI 视觉骨架");
        subtitle.setTextSize(12f);
        subtitle.setTextColor(color(activity, R.color.operit_text_hint));
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        slp.topMargin = dp(activity, 4);
        wrap.addView(subtitle, slp);

        // 信息卡片
        LinearLayout info = new LinearLayout(activity);
        info.setOrientation(LinearLayout.VERTICAL);
        // v1.157.0 Glass：实色平铺 → 玻璃磁贴（顶光 + 淡描边）
        info.setBackgroundResource(R.drawable.bg_glass_tile);
        info.setPadding(dp(activity, 14), dp(activity, 12), dp(activity, 14), dp(activity, 12));
        LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        ilp.topMargin = dp(activity, 16);
        wrap.addView(info, ilp);

        addInfoRow(activity, info, "版本", appVersion(activity));
        addInfoRow(activity, info, "包名", "com.digitallife");
        addInfoRow(activity, info, "角色", "小汐");
        addInfoRow(activity, info, "构建", "v" + appVersion(activity) + "-Operit-Shell");

        Button close = button(activity, wrap, "关闭");
        close.setOnClickListener(v -> {
            if (b.create().isShowing()) {
                // 通过反射关闭（避免引用外部 dialog）
            }
        });

        b.setView(wrap);
        android.app.AlertDialog dlg = b.create();
        // 绑定关闭按钮到真实 dialog
        close.setOnClickListener(v -> dlg.dismiss());
        dlg.show();
    }

    /** 键值信息行（左键右值）。v1.157.0 起公开，供二级页复用，避免各自重实现（issue #100） */
    public static void addInfoRow(Context c, LinearLayout root, String key, String val) {
        LinearLayout row = new LinearLayout(c);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(0, dp(c, 6), 0, dp(c, 6));
        TextView k = new TextView(c);
        k.setText(key);
        k.setTextSize(13f);
        k.setTextColor(color(c, R.color.operit_text_hint));
        k.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        row.addView(k);
        TextView v = new TextView(c);
        v.setText(val);
        v.setTextSize(13f);
        v.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        v.setTextColor(color(c, R.color.operit_text_primary));
        row.addView(v);
        root.addView(row);
    }

    /** Operit 风格 SectionHeader: 紫色大标题 + 副标题 + 顶部间距 */
    public static View sectionTitle(Context c, LinearLayout root, String title, String subtitle) {
        LinearLayout wrap = new LinearLayout(c);
        wrap.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams wlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        wlp.topMargin = dp(c, 20);
        wlp.bottomMargin = dp(c, 8);
        wrap.setLayoutParams(wlp);

        TextView t = new TextView(c);
        t.setText(title);
        t.setTextSize(15f);
        t.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        t.setLetterSpacing(0.04f);
        t.setTextColor(color(c, R.color.operit_accent));
        t.setIncludeFontPadding(false);
        wrap.addView(t);

        if (subtitle != null && !subtitle.isEmpty()) {
            TextView s = new TextView(c);
            s.setText(subtitle);
            s.setTextSize(11f);
            s.setTextColor(color(c, R.color.operit_text_hint));
            s.setIncludeFontPadding(false);
            LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            slp.topMargin = dp(c, 2);
            wrap.addView(s, slp);
        }

        root.addView(wrap);
        return wrap;
    }

    /** Operit 风格 list_tile: 圆角卡片 + 图标 + 标题 + 副标题 + 右箭头 */
    public static LinearLayout listTile(Context c, LinearLayout root, int iconRes,
                                        String title, String subtitle, View.OnClickListener onClick) {
        LinearLayout card = new LinearLayout(c);
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setBackgroundResource(R.drawable.bg_glass_tile);
        card.setPadding(dp(c, 14), dp(c, 14), dp(c, 14), dp(c, 14));
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        clp.topMargin = dp(c, 1);
        root.addView(card, clp);

        if (iconRes != 0) {
            ImageView ic = new ImageView(c);
            ic.setImageResource(iconRes);
            ic.setColorFilter(color(c, R.color.operit_accent));
            ic.setScaleType(ImageView.ScaleType.CENTER);
            card.addView(ic, new LinearLayout.LayoutParams(dp(c, 22), dp(c, 22)));
        }

        LinearLayout textCol = new LinearLayout(c);
        textCol.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        tlp.leftMargin = dp(c, 12);
        textCol.setLayoutParams(tlp);

        TextView t = new TextView(c);
        t.setText(title);
        t.setTextSize(14f);
        t.setTextColor(color(c, R.color.operit_text_primary));
        t.setIncludeFontPadding(false);
        textCol.addView(t);

        if (subtitle != null && !subtitle.isEmpty()) {
            TextView s = new TextView(c);
            s.setText(subtitle);
            s.setTextSize(11f);
            s.setTextColor(color(c, R.color.operit_text_hint));
            s.setIncludeFontPadding(false);
            LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            slp.topMargin = dp(c, 2);
            textCol.addView(s, slp);
        }
        card.addView(textCol);

        TextView chev = new TextView(c);
        chev.setText("\u203A");
        chev.setTextSize(20f);
        chev.setTextColor(color(c, R.color.operit_text_hint));
        chev.setIncludeFontPadding(false);
        chev.setGravity(Gravity.CENTER);
        card.addView(chev, new LinearLayout.LayoutParams(
                dp(c, 24), ViewGroup.LayoutParams.WRAP_CONTENT));

        if (onClick != null) {
            card.setClickable(true);
            card.setOnClickListener(onClick);
        }

        return card;
    }

    /** Operit 风格 switch_tile: 标题 + 副标题 + 右侧 Switch */
    public static Switch switchTile(Context c, LinearLayout root,
                                   String title, String subtitle, boolean checked) {
        LinearLayout card = new LinearLayout(c);
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setBackgroundResource(R.drawable.bg_glass_tile);
        card.setPadding(dp(c, 14), dp(c, 12), dp(c, 14), dp(c, 12));
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        clp.topMargin = dp(c, 1);
        root.addView(card, clp);

        LinearLayout textCol = new LinearLayout(c);
        textCol.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        textCol.setLayoutParams(tlp);

        TextView t = new TextView(c);
        t.setText(title);
        t.setTextSize(14f);
        t.setTextColor(color(c, R.color.operit_text_primary));
        t.setIncludeFontPadding(false);
        textCol.addView(t);

        if (subtitle != null && !subtitle.isEmpty()) {
            TextView s = new TextView(c);
            s.setText(subtitle);
            s.setTextSize(11f);
            s.setTextColor(color(c, R.color.operit_text_hint));
            s.setIncludeFontPadding(false);
            LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            slp.topMargin = dp(c, 2);
            textCol.addView(s, slp);
        }
        card.addView(textCol);

        Switch sw = new Switch(c);
        sw.setChecked(checked);
        card.addView(sw);

        return sw;
    }

    /** Operit 风格角色卡（OverviewCard）: 紫色背景 + 角色名 + 描述 + StatChip 行 */
    public static LinearLayout roleCard(Context c, LinearLayout root, String title, String subtitle) {
        LinearLayout card = new LinearLayout(c);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackgroundColor(color(c, R.color.brand_operit_dark));
        card.setPadding(dp(c, 20), dp(c, 20), dp(c, 20), dp(c, 20));
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        clp.topMargin = dp(c, 12);
        card.setLayoutParams(clp);
        root.addView(card);

        TextView t = new TextView(c);
        t.setText(title);
        t.setTextSize(20f);
        t.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        t.setTextColor(Color.WHITE);
        t.setIncludeFontPadding(false);
        card.addView(t);

        if (subtitle != null && !subtitle.isEmpty()) {
            TextView s = new TextView(c);
            s.setText(subtitle);
            s.setTextSize(12f);
            s.setTextColor(0xCCFFFFFF);
            s.setIncludeFontPadding(false);
            LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            slp.topMargin = dp(c, 6);
            card.addView(s, slp);
        }

        return card;
    }

    /** Operit 风格 stat chip: 圆角胶囊 + 文字 */
    public static TextView statChip(Context c, LinearLayout root, String text) {
        TextView chip = new TextView(c);
        chip.setText(text);
        chip.setTextSize(11f);
        chip.setTextColor(color(c, R.color.operit_text_primary));
        int bgColor = color(c, R.color.operit_surface_variant);
        int bgAlpha = (bgColor & 0x00FFFFFF) | 0x99000000;
        chip.setBackgroundColor(bgAlpha);
        chip.setPadding(dp(c, 10), dp(c, 5), dp(c, 10), dp(c, 5));
        chip.setIncludeFontPadding(false);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.rightMargin = dp(c, 6);
        lp.topMargin = dp(c, 4);
        root.addView(chip, lp);
        return chip;
    }
}
