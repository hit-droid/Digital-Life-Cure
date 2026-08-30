package com.digitallife.ui.shell;

import android.content.Context;
import android.graphics.drawable.Drawable;
import android.view.View;
import android.view.animation.AnimationUtils;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.digitallife.R;
import com.digitallife.ui.UiKit;

public class OperitDrawerItem extends LinearLayout {

    public interface OnClickListener {
        void onClick(OperitDrawerItem item);
    }

    private final ImageView icon;
    private final TextView title;
    private final TextView subtitle;
    private final TextView chevron;

    private OperitRoute route;
    private boolean selected = false;
    private OnClickListener listener;

    public OperitDrawerItem(Context ctx) {
        super(ctx);
        setOrientation(HORIZONTAL);
        setGravity(android.view.Gravity.CENTER_VERTICAL);
        int padH = UiKit.dp(ctx, 16);
        int padV = UiKit.dp(ctx, 4);
        setPadding(padH, padV, padH, padV);

        icon = new ImageView(ctx);
        icon.setLayoutParams(new LayoutParams(UiKit.dp(ctx, 20), UiKit.dp(ctx, 20)));
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

        setBackgroundResource(R.drawable.operit_drawer_item_background);
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
            Drawable ripple = getResources().getDrawable(R.drawable.operit_drawer_item_ripple, getContext().getTheme());
            setForeground(ripple);
        }
        setStateListAnimator(android.animation.AnimatorInflater.loadStateListAnimator(
                getContext(), R.animator.operit_drawer_item_elevation));
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
        subtitle.setText(route.subtitle);
        subtitle.setVisibility(VISIBLE);
        chevron.setVisibility(VISIBLE);
    }

    public void setSelected(boolean sel) {
        if (this.selected == sel) return;
        this.selected = sel;
        applyStyle();
    }

    public OperitRoute getRoute() { return route; }

    private void applyStyle() {
        if (selected) {
            title.setTextColor(UiKit.color(getContext(), R.color.operit_accent));
            icon.setColorFilter(UiKit.color(getContext(), R.color.operit_accent));
            chevron.setTextColor(UiKit.color(getContext(), R.color.operit_accent));
        } else {
            title.setTextColor(UiKit.color(getContext(), R.color.operit_text_primary));
            icon.setColorFilter(UiKit.color(getContext(), R.color.operit_text_secondary));
            chevron.setTextColor(UiKit.color(getContext(), R.color.operit_text_hint));
        }
    }
}
