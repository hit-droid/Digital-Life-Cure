package com.digitallife.ui.shell;

import android.content.Context;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.digitallife.R;
import com.digitallife.ui.UiKit;

/**
 * Operit 侧栏菜单项（v1.23.0 仿 Operit AI CompactNavigationDrawerItem）。
 * 一行：图标 + 标题 + 副标题，右侧 chevron，选中态高亮紫底。
 */
public class OperitDrawerItem extends LinearLayout {

    public interface OnClickListener {
        void onClick(OperitDrawerItem item);
    }

    private final ImageView icon;
    private final TextView title;
    private final TextView subtitle;
    private final TextView chevron;
    private final View selectedBar;

    private OperitRoute route;
    private boolean selected = false;
    private OnClickListener listener;

    public OperitDrawerItem(Context ctx) {
        super(ctx);
        setOrientation(HORIZONTAL);
        setGravity(android.view.Gravity.CENTER_VERTICAL);
        int padH = UiKit.dp(ctx, 16);
        int padV = UiKit.dp(ctx, 12);
        setPadding(padH, padV, padH, padV);

        icon = new ImageView(ctx);
        icon.setLayoutParams(new LayoutParams(UiKit.dp(ctx, 22), UiKit.dp(ctx, 22)));
        addView(icon);

        LinearLayout textCol = new LinearLayout(ctx);
        textCol.setOrientation(VERTICAL);
        LayoutParams textLp = new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f);
        textLp.leftMargin = UiKit.dp(ctx, 14);
        textCol.setLayoutParams(textLp);

        title = new TextView(ctx);
        title.setTextSize(14f);
        title.setTextColor(UiKit.color(ctx, R.color.operit_text_primary));
        title.setIncludeFontPadding(false);
        textCol.addView(title);

        subtitle = new TextView(ctx);
        subtitle.setTextSize(11f);
        subtitle.setTextColor(UiKit.color(ctx, R.color.operit_text_hint));
        subtitle.setIncludeFontPadding(false);
        subtitle.setVisibility(GONE);
        LayoutParams subLp = new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
        subLp.topMargin = UiKit.dp(ctx, 2);
        textCol.addView(subtitle, subLp);

        addView(textCol);

        chevron = new TextView(ctx);
        chevron.setText("\u203A");
        chevron.setTextSize(20f);
        chevron.setTextColor(UiKit.color(ctx, R.color.operit_text_hint));
        chevron.setIncludeFontPadding(false);
        addView(chevron);

        selectedBar = new View(ctx);
        selectedBar.setBackgroundColor(UiKit.color(ctx, R.color.brand_operit_light));
        selectedBar.setVisibility(GONE);
        selectedBar.setLayoutParams(new LayoutParams(UiKit.dp(ctx, 3), LayoutParams.MATCH_PARENT));

        setClickable(true);
        setFocusable(true);
        applyStyle();

        setOnClickListener(v -> {
            if (listener != null) listener.onClick(this);
        });
    }

    public void bind(OperitRoute route, OnClickListener l) {
        this.route = route;
        this.listener = l;
        icon.setImageResource(route.iconRes);
        title.setText(route.titleRes);
        subtitle.setVisibility(GONE);
        chevron.setVisibility(VISIBLE);
    }

    public void setSelected(boolean sel) {
        this.selected = sel;
        applyStyle();
    }

    public OperitRoute getRoute() { return route; }

    private void applyStyle() {
        if (selected) {
            setBackgroundColor(UiKit.color(getContext(), R.color.brand_operit_dark));
            title.setTextColor(UiKit.color(getContext(), R.color.operit_text_primary));
            icon.setColorFilter(UiKit.color(getContext(), R.color.operit_accent));
            chevron.setTextColor(UiKit.color(getContext(), R.color.operit_accent));
        } else {
            setBackgroundColor(0);
            title.setTextColor(UiKit.color(getContext(), R.color.operit_text_primary));
            icon.setColorFilter(UiKit.color(getContext(), R.color.operit_text_secondary));
            chevron.setTextColor(UiKit.color(getContext(), R.color.operit_text_hint));
        }
    }
}
