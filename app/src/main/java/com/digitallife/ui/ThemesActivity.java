package com.digitallife.ui;

import android.app.Activity;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.digitallife.R;
import com.digitallife.util.Settings;

/**
 * 主题设置页（v1.179.0）。
 *
 * <p>提供两项目前可安全落地的外观能力：<b>强调色</b>（全站生效，含文本/图标/按钮/顶栏/气泡）
 * 与<b>外观底</b>（深紫 / 纯黑 OLED）。二者都只改颜色取值，不引入第二套 XML 色板，
 * 故不触碰 color guard（issue #98）。</p>
 *
 * <p>改选后立即 {@link #recreate()}：重建 Activity 会重跑 {@link ThemeManager#apply}，
 * 于是整个页面按新主题重新解析属性，所见即所得。</p>
 */
public class ThemesActivity extends Activity {

    private Settings settings;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        ThemeManager.apply(this);
        super.onCreate(savedInstanceState);
        settings = new Settings(this);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundResource(R.drawable.bg_operit_page);

        LinearLayout topBar = UiKit.pageTopBar(this, getString(R.string.route_themes));
        root.addView(topBar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        ScrollView scroll = new ScrollView(this);
        scroll.setVerticalScrollBarEnabled(false);
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(UiKit.dp(this, 16), UiKit.dp(this, 16), UiKit.dp(this, 16), UiKit.dp(this, 24));

        buildAccentSection(content);
        buildAppearanceSection(content);

        TextView hint = new TextView(this);
        hint.setTextSize(11f);
        hint.setTextColor(UiKit.color(this, R.color.operit_text_hint));
        hint.setLineSpacing(3f, 1f);
        hint.setText("改动立即生效。主题只调整配色，不改变功能与数据。");
        content.addView(hint, UiKit.lp(this, 18));

        scroll.addView(content, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        setContentView(root);
    }

    // ==================== 强调色 ====================

    private void buildAccentSection(LinearLayout content) {
        UiKit.sectionTitle(this, content, "强调色", "影响文字、图标、按钮、顶栏与气泡的主色");

        LinearLayout panel = UiKit.panel(this, content);
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        panel.addView(row, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        String current = Accent.normalize(settings.getAccent());
        for (int i = 0; i < Accent.count(); i++) {
            final String key = Accent.keyAt(i);
            row.addView(buildAccentSwatch(key, key.equals(current)),
                    accentSwatchParams());
        }

        TextView sel = new TextView(this);
        sel.setTextSize(12f);
        sel.setTextColor(UiKit.color(this, R.color.operit_text_secondary));
        sel.setText("当前：" + Accent.label(current));
        panel.addView(sel, UiKit.lp(this, 10));
    }

    private LinearLayout.LayoutParams accentSwatchParams() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        return lp;
    }

    /** 单个色卡：圆点 + 名称；选中时描白边并加粗文字 */
    private View buildAccentSwatch(final String key, boolean selected) {
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER_HORIZONTAL);
        col.setPadding(0, UiKit.dp(this, 4), 0, UiKit.dp(this, 4));

        View dot = new View(this);
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.OVAL);
        // 色卡必须显示「该预设的固定颜色」，所以取字面值而非主题解析值：
        // 若走 UiKit.color，"幻紫" 会被当前强调色解析成同一色，六张卡就同色了。
        d.setColor(getResources().getColor(Accent.swatchRes(key)));
        if (selected) {
            d.setStroke(UiKit.dp(this, 2), UiKit.color(this, R.color.operit_text_primary));
        } else {
            d.setStroke(UiKit.dp(this, 1), UiKit.color(this, R.color.glass_border));
        }
        dot.setBackground(d);
        col.addView(dot, new LinearLayout.LayoutParams(UiKit.dp(this, 40), UiKit.dp(this, 40)));

        TextView label = new TextView(this);
        label.setText(Accent.label(key));
        label.setTextSize(11f);
        label.setTextColor(UiKit.color(this, R.color.operit_text_secondary));
        if (selected) {
            label.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
            label.setTextColor(UiKit.color(this, R.color.operit_text_primary));
        }
        LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        llp.topMargin = UiKit.dp(this, 4);
        col.addView(label, llp);

        final boolean alreadySel = selected;
        col.setOnClickListener(v -> {
            if (alreadySel) return;
            settings.setAccent(key);
            applyAndRefresh("强调色已切换为「" + Accent.label(key) + "」");
        });
        UiKit.pressScale(col);
        return col;
    }

    // ==================== 外观 ====================

    private void buildAppearanceSection(LinearLayout content) {
        UiKit.sectionTitle(this, content, "外观", "底色风格（均为深色，护眼省电）");
        String current = ThemeManager.normalizeAppearance(settings.getAppearance());
        addAppearanceRow(content, ThemeManager.APPEARANCE_DARK, "深紫",
                "经典 Operit 紫底，层次柔和", current.equals(ThemeManager.APPEARANCE_DARK));
        addAppearanceRow(content, ThemeManager.APPEARANCE_OLED, "纯黑（OLED）",
                "页面底压到纯黑，深色更纯净、省电", current.equals(ThemeManager.APPEARANCE_OLED));
    }

    private void addAppearanceRow(final LinearLayout root, final String key,
                                  String title, String subtitle, boolean selected) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setBackgroundResource(selected ? R.drawable.bg_card_active : R.drawable.bg_card);
        card.setPadding(UiKit.dp(this, 14), UiKit.dp(this, 14), UiKit.dp(this, 14), UiKit.dp(this, 14));
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        clp.topMargin = UiKit.dp(this, 6);
        root.addView(card, clp);

        LinearLayout textCol = new LinearLayout(this);
        textCol.setOrientation(LinearLayout.VERTICAL);
        textCol.setLayoutParams(new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView t = new TextView(this);
        t.setText(title);
        t.setTextSize(14f);
        t.setTextColor(UiKit.color(this, R.color.operit_text_primary));
        t.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        t.setIncludeFontPadding(false);
        textCol.addView(t);

        TextView s = new TextView(this);
        s.setText(subtitle);
        s.setTextSize(11f);
        s.setTextColor(UiKit.color(this, R.color.operit_text_hint));
        s.setIncludeFontPadding(false);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        slp.topMargin = UiKit.dp(this, 2);
        textCol.addView(s, slp);
        card.addView(textCol);

        TextView mark = new TextView(this);
        mark.setText(selected ? "✓ 使用中" : "");
        mark.setTextSize(12f);
        mark.setTextColor(UiKit.color(this, R.color.operit_accent));
        mark.setIncludeFontPadding(false);
        card.addView(mark);

        if (!selected) {
            card.setOnClickListener(v -> {
                settings.setAppearance(key);
                applyAndRefresh("外观已切换为「" + ThemeManager.appearanceLabel(key) + "」");
            });
            UiKit.pressScale(card);
        }
    }

    // ==================== 生效 ====================

    /** 落盘后重建本页，让新主题立刻套用（重建会重跑 ThemeManager.apply） */
    private void applyAndRefresh(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
        recreate();
    }
}
