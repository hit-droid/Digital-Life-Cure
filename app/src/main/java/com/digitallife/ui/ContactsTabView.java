package com.digitallife.ui;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.Typeface;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.digitallife.R;
import com.digitallife.model.ModelInspector;
import com.digitallife.model.ModelManager;
import com.digitallife.render.Live2DNative;
import com.digitallife.util.ChatStore;

import java.util.List;

/**
 * 微信式「通讯录」Tab：模型联系人。
 * 列出所有已安装的 Live2D 模型（内置 + 已导入），
 * 每个联系人绑定独立会话（session_key=模型名），点击进入与该模型的单独对话。
 */
public class ContactsTabView extends LinearLayout {

    public interface Listener {
        void onOpenModel(String modelName);
    }

    private final Activity activity;
    private final Listener listener;
    private LinearLayout listContainer;

    public ContactsTabView(Activity activity, Listener listener) {
        super(activity);
        this.activity = activity;
        this.listener = listener;
        setOrientation(LinearLayout.VERTICAL);
        setBackgroundColor(UiKit.color(activity, R.color.operit_bg));
        buildUi();
    }

    private void buildUi() {
        TextView header = new TextView(activity);
        header.setText("模型联系人 — 点一个模型，和 TA 单独聊聊");
        header.setTextSize(13f);
        header.setTextColor(UiKit.color(activity, R.color.operit_text_secondary));
        header.setPadding(UiKit.dp(activity, 12), UiKit.dp(activity, 10),
                UiKit.dp(activity, 12), UiKit.dp(activity, 2));
        addView(header, UiKit.lp(activity, 0));

        ScrollView scroll = new ScrollView(activity);
        scroll.setVerticalScrollBarEnabled(false);
        listContainer = new LinearLayout(activity);
        listContainer.setOrientation(LinearLayout.VERTICAL);
        listContainer.setPadding(UiKit.dp(activity, 12), UiKit.dp(activity, 6),
                UiKit.dp(activity, 12), UiKit.dp(activity, 12));
        scroll.addView(listContainer, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
    }

    public void refresh() {
        if (listContainer == null) return;
        listContainer.removeAllViews();
        int count = 0;
        try {
            count = Live2DNative.nativeGetModelCount();
        } catch (Throwable t) {
            count = 0;
        }
        List<String> imported = ModelManager.listImportedModelDirs(activity);
        if (count <= 0) {
            TextView empty = new TextView(activity);
            empty.setText("暂无可用模型。\n可在对话页打开「护理大脑」，发送模型 zip 压缩包完成安装。");
            empty.setTextSize(13f);
            empty.setTextColor(UiKit.color(activity, R.color.operit_text_secondary));
            empty.setGravity(Gravity.CENTER);
            empty.setPadding(0, UiKit.dp(activity, 40), 0, 0);
            listContainer.addView(empty);
            return;
        }
        for (int i = 0; i < count; i++) {
            final String name;
            try {
                name = Live2DNative.nativeGetModelDirName(i);
            } catch (Throwable t) {
                continue;
            }
            if (name == null || name.isEmpty()) continue;
            final boolean isImported = imported.contains(name);
            final boolean hasMotions = ModelInspector.hasUsableMotions(activity, name);
            listContainer.addView(buildContactCard(name, isImported, hasMotions));
        }
    }

    private View buildContactCard(final String name, final boolean isImported, final boolean hasMotions) {
        LinearLayout card = new LinearLayout(activity);
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setElevation(UiKit.dp(activity, 2));
        card.setPadding(UiKit.dp(activity, 12), UiKit.dp(activity, 10),
                UiKit.dp(activity, 8), UiKit.dp(activity, 10));
        UiKit.ripple(activity, card, activity.getDrawable(R.drawable.bg_card), 20);
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        clp.bottomMargin = UiKit.dp(activity, 8);
        card.setLayoutParams(clp);

        TextView avatar = new TextView(activity);
        avatar.setTextSize(20f);
        avatar.setTypeface(Typeface.DEFAULT_BOLD);
        avatar.setTextColor(Color.WHITE);
        avatar.setGravity(Gravity.CENTER);
        avatar.setText(name.length() > 0 ? name.substring(0, 1).toUpperCase(java.util.Locale.ROOT) : "?");
        android.graphics.drawable.GradientDrawable g = new android.graphics.drawable.GradientDrawable();
        g.setShape(android.graphics.drawable.GradientDrawable.RECTANGLE);
        g.setCornerRadius(UiKit.dp(activity, 12));
        g.setColor(isImported ? UiKit.color(activity, R.color.tag_chat) : UiKit.color(activity, R.color.tag_builtin));
        avatar.setBackground(g);
        LinearLayout.LayoutParams avLp = new LinearLayout.LayoutParams(
                UiKit.dp(activity, 44), UiKit.dp(activity, 44));
        card.addView(avatar, avLp);

        LinearLayout info = new LinearLayout(activity);
        info.setOrientation(LinearLayout.VERTICAL);
        info.setPadding(UiKit.dp(activity, 10), 0, 0, 0);

        LinearLayout nameRow = new LinearLayout(activity);
        nameRow.setOrientation(LinearLayout.HORIZONTAL);
        nameRow.setGravity(Gravity.CENTER_VERTICAL);
        TextView tvName = new TextView(activity);
        tvName.setText(name);
        tvName.setTextSize(15f);
        tvName.setTypeface(Typeface.DEFAULT_BOLD);
        tvName.setTextColor(UiKit.color(activity, R.color.operit_text_primary));
        nameRow.addView(tvName, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        TextView tag = new TextView(activity);
        tag.setText(isImported ? "已导入" : "内置");
        int tagColor = UiKit.color(activity,
                isImported ? R.color.tag_chat : R.color.tag_builtin);
        tag.setTextColor(tagColor);
        tag.setBackground(tagBg(tagColor));
        tag.setPadding(UiKit.dp(activity, 6), UiKit.dp(activity, 1),
                UiKit.dp(activity, 6), UiKit.dp(activity, 1));
        nameRow.addView(tag);
        info.addView(nameRow);

        TextView status = new TextView(activity);
        status.setText(hasMotions ? "动作齐全，可以自由活动" : "暂无动作文件（可通过护理大脑补全）");
        status.setTextSize(13f);
        status.setTextColor(UiKit.color(activity, R.color.operit_text_secondary));
        info.addView(status, UiKit.lp(activity, 2));

        ChatStore.StoredMsg last = new ChatStore(activity).getLastMessage("model_" + name);
        TextView lastTv = new TextView(activity);
        if (last != null && last.content != null && !last.content.isEmpty()) {
            lastTv.setText("assistant".equals(last.role) ? "她说：" + last.content : "我：" + last.content);
        } else {
            lastTv.setText("还没聊过，点进去打声招呼吧");
        }
        lastTv.setTextSize(12f);
        lastTv.setTextColor(UiKit.color(activity, R.color.operit_text_hint));
        lastTv.setMaxLines(1);
        info.addView(lastTv, UiKit.lp(activity, 1));
        card.addView(info, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));

        android.widget.ImageView arrow = new android.widget.ImageView(activity);
        arrow.setImageResource(R.drawable.ic_chevron_right);
        arrow.setScaleType(android.widget.ImageView.ScaleType.CENTER);
        LinearLayout.LayoutParams arp = new LinearLayout.LayoutParams(
                UiKit.dp(activity, 24), UiKit.dp(activity, 24));
        card.addView(arrow, arp);

        card.setOnClickListener(v -> {
            if (listener != null) {
                listener.onOpenModel(name);
            } else {
                Toast.makeText(activity, "请先回到主界面", Toast.LENGTH_SHORT).show();
            }
        });
        return card;
    }

    private android.graphics.drawable.GradientDrawable tagBg(int color) {
        android.graphics.drawable.GradientDrawable g = new android.graphics.drawable.GradientDrawable();
        g.setShape(android.graphics.drawable.GradientDrawable.RECTANGLE);
        g.setCornerRadius(UiKit.dp(activity, 8));
        g.setColor(Color.argb(30, Color.red(color), Color.green(color), Color.blue(color)));
        g.setStroke(1, color);
        return g;
    }
}
