package com.digitallife.ui.shell;

import android.content.Context;
import android.graphics.drawable.Drawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.digitallife.R;
import com.digitallife.ui.UiKit;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

public class OperitDrawer extends LinearLayout {

    public interface OnRouteSelected {
        void onRouteSelected(OperitRoute route);
    }

    private final Map<OperitRoute, OperitDrawerItem> itemMap = new EnumMap<>(OperitRoute.class);
    private final OnRouteSelected listener;

    public OperitDrawer(Context ctx, OnRouteSelected listener) {
        super(ctx);
        this.listener = listener;
        setOrientation(VERTICAL);
        setBackgroundResource(R.drawable.bg_operit_drawer);
        setElevation(UiKit.dp(ctx, 8));
        setClipToOutline(true);
        int padH = UiKit.dp(ctx, 8);
        setPadding(padH, 0, padH, 0);
        setLayoutParams(new FrameLayout.LayoutParams(
                UiKit.dp(ctx, 280), ViewGroup.LayoutParams.MATCH_PARENT,
                Gravity.START));
    }

    public void build(OperitRoute currentRoute) {
        removeAllViews();
        itemMap.clear();

        addView(buildHeader());
        addView(buildDivider());
        addView(buildSectionTitle("数字生命"));
        addRouteItem(OperitRoute.CHAT);
        addRouteItem(OperitRoute.CONTACTS);
        addRouteItem(OperitRoute.DISCOVER);
        addRouteItem(OperitRoute.PLUGIN);
        addRouteItem(OperitRoute.SETTINGS);

        addView(buildDivider());
        addView(buildSectionTitle("高级"));
        addRouteItem(OperitRoute.MEMORY);
        addRouteItem(OperitRoute.CARE);
        addRouteItem(OperitRoute.THEMES);
        addRouteItem(OperitRoute.DEVELOPER);
        addRouteItem(OperitRoute.ABOUT);

        setSelected(currentRoute);
    }

    public void setSelected(OperitRoute route) {
        for (Map.Entry<OperitRoute, OperitDrawerItem> e : itemMap.entrySet()) {
            e.getValue().setSelected(e.getKey() == route);
        }
    }

    private View buildHeader() {
        LinearLayout header = new LinearLayout(getContext());
        header.setOrientation(VERTICAL);
        header.setPadding(UiKit.dp(getContext(), 20), UiKit.dp(getContext(), 56),
                UiKit.dp(getContext(), 20), UiKit.dp(getContext(), 16));

        TextView brand = new TextView(getContext());
        brand.setText("数字生命");
        brand.setTextSize(20f);
        brand.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        brand.setTextColor(UiKit.color(getContext(), R.color.operit_text_primary));
        header.addView(brand);

        TextView version = new TextView(getContext());
        version.setText("v1.23.0 · 小汐");
        version.setTextSize(11f);
        version.setTextColor(UiKit.color(getContext(), R.color.operit_text_hint));
        LinearLayout.LayoutParams vlp = new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
        vlp.topMargin = UiKit.dp(getContext(), 4);
        header.addView(version, vlp);

        return header;
    }

    private View buildDivider() {
        View divider = new View(getContext());
        divider.setBackgroundColor(UiKit.color(getContext(), R.color.operit_divider));
        LinearLayout.LayoutParams dlp = new LayoutParams(LayoutParams.MATCH_PARENT, 1);
        dlp.leftMargin = UiKit.dp(getContext(), 16);
        dlp.rightMargin = UiKit.dp(getContext(), 16);
        dlp.topMargin = UiKit.dp(getContext(), 8);
        dlp.bottomMargin = UiKit.dp(getContext(), 8);
        divider.setLayoutParams(dlp);
        return divider;
    }

    private View buildSectionTitle(String title) {
        TextView tv = new TextView(getContext());
        tv.setText(title);
        tv.setTextSize(11f);
        tv.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        tv.setTextColor(UiKit.color(getContext(), R.color.operit_accent));
        tv.setIncludeFontPadding(false);
        LinearLayout.LayoutParams lp = new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
        lp.leftMargin = UiKit.dp(getContext(), 20);
        lp.topMargin = UiKit.dp(getContext(), 12);
        lp.bottomMargin = UiKit.dp(getContext(), 4);
        tv.setLayoutParams(lp);
        return tv;
    }

    private void addRouteItem(OperitRoute route) {
        OperitDrawerItem item = new OperitDrawerItem(getContext());
        item.bind(route, clicked -> {
            if (listener != null) listener.onRouteSelected(clicked.getRoute());
        });
        itemMap.put(route, item);
        addView(item);
    }
}
