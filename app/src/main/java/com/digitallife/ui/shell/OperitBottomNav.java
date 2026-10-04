package com.digitallife.ui.shell;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.digitallife.R;
import com.digitallife.ui.UiKit;

import java.util.EnumMap;
import java.util.Map;

/**
 * 主壳底部 5 Tab 导航（仿 Operit AI / Material 3 NavigationBar）。
 *
 * <p>只承载 {@link OperitRoute#primary} 的 5 个主 Tab；记忆/护理/控制台等高级路由
 * 仍走左侧侧栏。选中态＝图标胶囊底 + 强调色图标与加粗标签，未选中＝次级灰；
 * 胶囊底始终占位（仅切换颜色），保证选中/未选中时图标与文字都不发生位移。</p>
 *
 * <p>外观：四角圆角的中性面板，由外层 {@code MainActivity} 加左右/底部边距后，
 * 在深色页面底色上呈现为「浮起的一枚胶囊」。</p>
 */
public class OperitBottomNav extends LinearLayout {

    public interface OnTabSelected {
        void onTabSelected(OperitRoute route);
    }

    private static final OperitRoute[] TABS = {
            OperitRoute.CHAT, OperitRoute.CONTACTS, OperitRoute.DISCOVER,
            OperitRoute.PLUGIN, OperitRoute.SETTINGS
    };

    private final Map<OperitRoute, Item> items = new EnumMap<>(OperitRoute.class);
    private OperitRoute selected = OperitRoute.CHAT;

    public OperitBottomNav(Context ctx, OnTabSelected listener) {
        super(ctx);
        setOrientation(HORIZONTAL);
        setBackgroundResource(R.drawable.bg_operit_bottom_nav);
        setElevation(UiKit.dp(ctx, 10));
        int padV = UiKit.dp(ctx, 8);
        setPadding(UiKit.dp(ctx, 4), padV, UiKit.dp(ctx, 4), padV);
        setMinimumHeight(UiKit.dp(ctx, 60));

        for (final OperitRoute route : TABS) {
            Item item = new Item(ctx, route);
            item.setOnClickListener(v -> {
                if (listener != null) listener.onTabSelected(route);
            });
            items.put(route, item);
            addView(item, new LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        }
        applySelection();
    }

    /** 同步选中态；传入非主 Tab 路由时忽略（保持当前主 Tab 高亮） */
    public void setSelected(OperitRoute route) {
        if (route == null || !items.containsKey(route) || route == selected) return;
        selected = route;
        applySelection();
    }

    public OperitRoute selected() {
        return selected;
    }

    private void applySelection() {
        for (Map.Entry<OperitRoute, Item> e : items.entrySet()) {
            e.getValue().setActive(e.getKey() == selected);
        }
    }

    /** 单个 Tab：图标（选中时套胶囊底）+ 文案 */
    private static class Item extends LinearLayout {

        private final Context ctx;
        private final ImageView icon;
        private final TextView label;
        private final GradientDrawable pill;

        Item(Context ctx, OperitRoute route) {
            super(ctx);
            this.ctx = ctx;
            setOrientation(VERTICAL);
            setGravity(Gravity.CENTER_HORIZONTAL);
            setPadding(0, UiKit.dp(ctx, 3), 0, UiKit.dp(ctx, 3));
            setBackgroundResource(R.drawable.operit_nav_item_ripple);
            setClickable(true);
            setFocusable(true);

            // 胶囊底始终占位（未选中为透明），保证选中/未选中图标不位移
            pill = new GradientDrawable();
            pill.setShape(GradientDrawable.RECTANGLE);
            pill.setCornerRadius(UiKit.dp(ctx, 15));
            pill.setColor(Color.TRANSPARENT);

            FrameLayout iconWrap = new FrameLayout(ctx);
            iconWrap.setBackground(pill);
            addView(iconWrap, new LayoutParams(UiKit.dp(ctx, 56), UiKit.dp(ctx, 30)));

            icon = new ImageView(ctx);
            icon.setImageResource(route.iconRes);
            icon.setScaleType(ImageView.ScaleType.CENTER);
            iconWrap.addView(icon, new FrameLayout.LayoutParams(
                    UiKit.dp(ctx, 22), UiKit.dp(ctx, 22), Gravity.CENTER));

            label = new TextView(ctx);
            label.setText(route.titleRes);
            label.setTextSize(11f);
            label.setIncludeFontPadding(false);
            LayoutParams llp = new LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            llp.topMargin = UiKit.dp(ctx, 4);
            addView(label, llp);
        }

        void setActive(boolean active) {
            pill.setColor(active
                    ? UiKit.color(ctx, R.color.operit_nav_pill)
                    : Color.TRANSPARENT);
            int c = UiKit.color(ctx, active ? R.color.operit_accent : R.color.operit_text_hint);
            icon.setColorFilter(c);
            label.setTextColor(c);
            label.setTypeface(active ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
        }
    }
}
