package com.digitallife.ui;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.digitallife.R;
import com.digitallife.brain.AICore;
import com.digitallife.brain.EmotionState;
import com.digitallife.service.PetService;
import com.digitallife.util.MemoryStore;
import com.digitallife.util.ThoughtStore;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * 微信式「发现」Tab：模型内心世界。
 * 顶部展示当前情绪状态，往下是内心独白 feed（ThoughtStore）与记忆摘要（MemoryStore）。
 */
public class DiscoverTabView extends LinearLayout {

    private final Activity activity;
    private final ThoughtStore thoughtStore;
    private final MemoryStore memory;
    private LinearLayout container;

    public DiscoverTabView(Activity activity) {
        super(activity);
        this.activity = activity;
        this.thoughtStore = new ThoughtStore(activity);
        this.memory = new MemoryStore(activity);
        setOrientation(LinearLayout.VERTICAL);
        setBackgroundColor(UiKit.color(activity, R.color.operit_bg));
        buildUi();
    }

    private void buildUi() {
        ScrollView scroll = new ScrollView(activity);
        scroll.setVerticalScrollBarEnabled(false);
        container = new LinearLayout(activity);
        container.setOrientation(LinearLayout.VERTICAL);
        container.setPadding(UiKit.dp(activity, 12), UiKit.dp(activity, 8),
                UiKit.dp(activity, 12), UiKit.dp(activity, 16));
        scroll.addView(container, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
    }

    public void refresh() {
        if (container == null) return;
        container.removeAllViews();
        renderEmotionCard();
        renderThoughts();
        renderMemory();
    }

    private void renderEmotionCard() {
        LinearLayout card = UiKit.card(activity, container, "当前心情");
        AICore ai = getAiCore();
        EmotionState emotion = ai != null ? ai.getEmotion() : null;
        if (ai == null) {
            TextView tip = new TextView(activity);
            tip.setText("桌宠未运行，无法读取实时情绪。\n启动桌宠后，这里会显示她的心情、亲密度与精力值。");
            tip.setTextSize(13f);
            tip.setTextColor(UiKit.color(activity, R.color.operit_text_secondary));
            tip.setLineSpacing(3f, 1f);
            card.addView(tip, UiKit.lp(activity, 0));
            return;
        }
        String mood = emotion != null ? emotion.describe() : "未知";
        TextView tvMood = new TextView(activity);
        tvMood.setText(mood);
        tvMood.setTextSize(18f);
        tvMood.setTypeface(Typeface.DEFAULT_BOLD);
        tvMood.setTextColor(UiKit.color(activity, R.color.operit_accent));
        card.addView(tvMood, UiKit.lp(activity, 0));

        if (emotion != null) {
            TextView tvMeta = new TextView(activity);
            tvMeta.setText("亲密度 " + pct(emotion.getIntimacy())
                    + "  ·  精力 " + pct(emotion.getEnergy()));
            tvMeta.setTextSize(13f);
            tvMeta.setTextColor(UiKit.color(activity, R.color.operit_text_secondary));
            card.addView(tvMeta, UiKit.lp(activity, 4));

            for (String dim : EmotionState.DIMS) {
                float val = emotion.get(dim);
                LinearLayout row = new LinearLayout(activity);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setGravity(Gravity.CENTER_VERTICAL);
                TextView label = new TextView(activity);
                label.setText(dimName(dim));
                label.setTextSize(13f);
                label.setTextColor(UiKit.color(activity, R.color.operit_text_secondary));
                row.addView(label, new LinearLayout.LayoutParams(
                        UiKit.dp(activity, 56), ViewGroup.LayoutParams.WRAP_CONTENT));

                LinearLayout track = new LinearLayout(activity);
                track.setOrientation(LinearLayout.HORIZONTAL);
                track.setBackgroundResource(R.drawable.bg_track);
                LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(0, UiKit.dp(activity, 8), 1);
                tl.leftMargin = UiKit.dp(activity, 4);
                tl.rightMargin = UiKit.dp(activity, 4);
                track.setLayoutParams(tl);

                View fill = new View(activity);
                GradientDrawable fg = new GradientDrawable();
                fg.setCornerRadius(UiKit.dp(activity, 4));
                fg.setColor(dimColor(dim));
                fill.setBackground(fg);
                LinearLayout.LayoutParams fl = new LinearLayout.LayoutParams(0, UiKit.dp(activity, 8), val);
                fill.setLayoutParams(fl);
                track.addView(fill);
                row.addView(track);

                TextView pv = new TextView(activity);
                pv.setText(pct(val));
                pv.setTextSize(11f);
                pv.setTextColor(UiKit.color(activity, R.color.operit_text_secondary));
                row.addView(pv);
                card.addView(row, UiKit.lp(activity, 3));
            }
        }
    }

    private void renderThoughts() {
        LinearLayout card = UiKit.card(activity, container, "内心独白");
        List<ThoughtStore.Thought> thoughts = thoughtStore.getRecentThoughts(30);
        if (thoughts.isEmpty()) {
            TextView tip = new TextView(activity);
            tip.setText("还没有内心独白。\n桌宠运行后，她会在安静时默默思考，把这些想法记录在这里。");
            tip.setTextSize(13f);
            tip.setTextColor(UiKit.color(activity, R.color.operit_text_secondary));
            tip.setLineSpacing(3f, 1f);
            card.addView(tip, UiKit.lp(activity, 0));
            return;
        }
        // 倒序：最新的在最上面
        for (int i = thoughts.size() - 1; i >= 0; i--) {
            ThoughtStore.Thought t = thoughts.get(i);
            TextView time = new TextView(activity);
            time.setText(new SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(new Date(t.timestamp)));
            time.setTextSize(10f);
            time.setTextColor(UiKit.color(activity, R.color.operit_text_secondary));
            card.addView(time, UiKit.lp(activity, 6));

            TextView text = new TextView(activity);
            text.setText(t.text);
            text.setTextSize(14f);
            text.setTextColor(UiKit.color(activity, R.color.operit_text_primary));
            text.setLineSpacing(3f, 1f);
            text.setBackgroundResource(R.drawable.bg_bubble_ai);
            text.setPadding(UiKit.dp(activity, 10), UiKit.dp(activity, 8),
                    UiKit.dp(activity, 10), UiKit.dp(activity, 8));
            card.addView(text, UiKit.lp(activity, 2));
        }
    }

    private void renderMemory() {
        LinearLayout card = UiKit.card(activity, container, "记忆与事实");

        List<MemoryStore.DailySummary> summaries = memory.getRecentSummaries(3);
        if (!summaries.isEmpty()) {
            TextView sh = new TextView(activity);
            sh.setText("最近日记");
            sh.setTextSize(13f);
            sh.setTypeface(Typeface.DEFAULT_BOLD);
            sh.setTextColor(UiKit.color(activity, R.color.operit_accent));
            card.addView(sh, UiKit.lp(activity, 4));
            for (MemoryStore.DailySummary s : summaries) {
                TextView st = new TextView(activity);
                st.setText(s.date + "：" + s.summary);
                st.setTextSize(13f);
                st.setTextColor(UiKit.color(activity, R.color.operit_text_secondary));
                st.setLineSpacing(2f, 1f);
                card.addView(st, UiKit.lp(activity, 2));
            }
        }

        List<MemoryStore.Fact> facts = memory.getRecentFacts(20);
        if (!facts.isEmpty()) {
            TextView fh = new TextView(activity);
            fh.setText("记住的事");
            fh.setTextSize(13f);
            fh.setTypeface(Typeface.DEFAULT_BOLD);
            fh.setTextColor(UiKit.color(activity, R.color.operit_accent));
            card.addView(fh, UiKit.lp(activity, 6));
            for (MemoryStore.Fact f : facts) {
                TextView ft = new TextView(activity);
                ft.setText("· " + f.content);
                ft.setTextSize(13f);
                ft.setTextColor(UiKit.color(activity, R.color.operit_text_primary));
                ft.setLineSpacing(2f, 1f);
                card.addView(ft, UiKit.lp(activity, 2));
            }
        }
        if (summaries.isEmpty() && facts.isEmpty()) {
            TextView tip = new TextView(activity);
            tip.setText("还没有记忆。和她说的话会被记录成长期记忆，在这里沉淀。");
            tip.setTextSize(13f);
            tip.setTextColor(UiKit.color(activity, R.color.operit_text_secondary));
            tip.setLineSpacing(3f, 1f);
            card.addView(tip, UiKit.lp(activity, 0));
        }
    }

    private AICore getAiCore() {
        PetService svc = PetService.getInstance();
        return svc != null ? svc.getAiCore() : null;
    }

    private String pct(float v) {
        return String.format(Locale.getDefault(), "%.0f%%", v * 100f);
    }

    private String dimName(String dim) {
        switch (dim) {
            case "happy": return "开心";
            case "sad": return "难过";
            case "angry": return "生气";
            case "surprised": return "惊讶";
            case "shy": return "害羞";
            case "calm": return "平静";
            default: return dim;
        }
    }

    private int dimColor(String dim) {
        switch (dim) {
            case "happy": return Color.rgb(255, 180, 60);
            case "sad": return Color.rgb(100, 150, 220);
            case "angry": return Color.rgb(220, 90, 80);
            case "surprised": return Color.rgb(255, 160, 100);
            case "shy": return Color.rgb(240, 130, 160);
            case "calm": return Color.rgb(120, 180, 130);
            default: return Color.GRAY;
        }
    }
}
