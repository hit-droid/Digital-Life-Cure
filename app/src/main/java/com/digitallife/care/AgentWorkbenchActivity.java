package com.digitallife.care;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Typeface;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.digitallife.R;
import com.digitallife.render.Live2DNative;
import com.digitallife.ui.UiKit;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Agent 工作台：把 Agent **已经配置好的东西**集中看一眼。
 *
 * <p>背景（v1.164.0）：v1.163.0 给 Agent 页底部 chips 接了「模型管理 ›」真跳转，
 * 但「工作流 / 定时」两类能力**只能让 AI 在对话里念一遍**——本机其实早把它们持久化
 * 在 {@code care_workflows} / {@code care_schedules} 两个 prefs 里（见 {@code CareTools}），
 * 用户没有任何界面能看到自己存了什么、什么时候会触发。本页补上这个「看」的入口。</p>
 *
 * <p>只读展示，不做增删改：创建仍回到对话里让 AI 编排（那是 Agent 的主场），
 * 这里只负责「现状可见」。动作列表要按模型逐个解析、不是一份平铺清单，故仍留在
 * 「模型管理」页（其入口卡片在本页给出）。</p>
 */
public class AgentWorkbenchActivity extends Activity {

    /** 打开时定位到哪个小节；{@code null} = 顶部 */
    public static final String EXTRA_SECTION = "wb_section";
    public static final String SECTION_WORKFLOW = "workflow";
    public static final String SECTION_SCHEDULE = "schedule";

    /** 与 {@code CareTools} 中的常量保持一致（工作流 / 定时任务的落盘位置） */
    private static final String PREFS_WORKFLOW = "care_workflows";
    private static final String PREFS_SCHEDULE = "care_schedules";

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        buildUi();
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundResource(R.drawable.bg_operit_page);
        root.addView(UiKit.pageTopBar(this, "工作台"),
                new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        ScrollView scroll = new ScrollView(this);
        scroll.setVerticalScrollBarEnabled(false);
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(dp(14), dp(4), dp(14), dp(16));
        scroll.addView(body, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        setContentView(root);

        buildModelsSection(body);
        View wfHeader = buildWorkflowSection(body);
        View scHeader = buildScheduleSection(body);

        // 从 Agent 页 chips 进来时定位到对应小节（布局完成后再滚，Top 才有值）
        String section = getIntent().getStringExtra(EXTRA_SECTION);
        View target = SECTION_WORKFLOW.equals(section) ? wfHeader
                : SECTION_SCHEDULE.equals(section) ? scHeader : null;
        if (target != null) {
            scroll.post(() -> scroll.smoothScrollTo(0, target.getTop()));
        }
    }

    // ============ 模型与动作（入口） ============

    private void buildModelsSection(LinearLayout body) {
        UiKit.sectionTitle(this, body, "模型与动作", "导入 / 切换模型，查看动作与资源");
        LinearLayout panel = UiKit.panel(this, body);

        int count = 0;
        try {
            count = Live2DNative.nativeGetModelCount();
        } catch (Throwable ignored) {
        }
        TextView tv = new TextView(this);
        tv.setText(count > 0
                ? "已安装 " + count + " 个模型"
                : "还没有模型，可在对话里发送模型 zip 安装");
        tv.setTextSize(13f);
        tv.setTextColor(getColorCompat(R.color.operit_text_secondary));
        tv.setIncludeFontPadding(false);
        panel.addView(tv);

        Button open = UiKit.secondaryButton(this, panel, "打开模型管理 ›");
        open.setOnClickListener(v -> startActivity(new Intent(this, CareModelsActivity.class)));
    }

    // ============ 工作流 ============

    private View buildWorkflowSection(LinearLayout body) {
        View header = UiKit.sectionTitle(this, body, "工作流", "Agent 编排的多步骤流程");
        LinearLayout panel = UiKit.panel(this, body);
        List<String[]> rows = readWorkflows();
        if (rows.isEmpty()) {
            panel.addView(hint("还没有工作流。在对话里说「帮我建一个工作流」即可创建。"));
        } else {
            for (String[] r : rows) panel.addView(row(r[0], r[1]));
        }
        return header;
    }

    private List<String[]> readWorkflows() {
        List<String[]> out = new ArrayList<>();
        try {
            Map<String, ?> all = getSharedPreferences(PREFS_WORKFLOW, MODE_PRIVATE).getAll();
            for (String name : all.keySet()) {
                int steps = 0;
                try {
                    JSONArray a = new JSONObject(String.valueOf(all.get(name))).optJSONArray("steps");
                    if (a != null) steps = a.length();
                } catch (Exception ignored) {
                }
                out.add(new String[]{name, steps + " 步"});
            }
        } catch (Throwable ignored) {
        }
        Collections.sort(out, (a, b) -> a[0].compareTo(b[0]));
        return out;
    }

    // ============ 定时任务 ============

    private View buildScheduleSection(LinearLayout body) {
        View header = UiKit.sectionTitle(this, body, "定时任务", "按 cron 表达式周期触发工作流");
        LinearLayout panel = UiKit.panel(this, body);
        List<String[]> rows = readSchedules();
        if (rows.isEmpty()) {
            panel.addView(hint("还没有定时任务。在对话里说「帮我建一个定时任务」即可创建。"));
        } else {
            for (String[] r : rows) panel.addView(row(r[0], r[1]));
        }
        return header;
    }

    private List<String[]> readSchedules() {
        List<String[]> out = new ArrayList<>();
        try {
            Map<String, ?> all = getSharedPreferences(PREFS_SCHEDULE, MODE_PRIVATE).getAll();
            for (String name : all.keySet()) {
                String cron = "?";
                String workflow = "?";
                try {
                    JSONObject o = new JSONObject(String.valueOf(all.get(name)));
                    cron = o.optString("cronExpr", "?");
                    workflow = o.optString("workflowName", "?");
                } catch (Exception ignored) {
                }
                out.add(new String[]{name, "cron " + cron + " → " + workflow});
            }
        } catch (Throwable ignored) {
        }
        Collections.sort(out, (a, b) -> a[0].compareTo(b[0]));
        return out;
    }

    // ============ 行 / 空态 ============

    /** 一条只读记录：主标题 + 次级说明 */
    private View row(String title, String subtitle) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        blp.topMargin = dp(10);
        box.setLayoutParams(blp);

        TextView t = new TextView(this);
        t.setText(title);
        t.setTextSize(14f);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setTextColor(getColorCompat(R.color.operit_text_primary));
        t.setIncludeFontPadding(false);
        box.addView(t);

        TextView s = new TextView(this);
        s.setText(subtitle);
        s.setTextSize(12f);
        s.setTextColor(getColorCompat(R.color.operit_text_hint));
        s.setIncludeFontPadding(false);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        slp.topMargin = dp(2);
        box.addView(s, slp);
        return box;
    }

    private TextView hint(String text) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(13f);
        tv.setTextColor(getColorCompat(R.color.operit_text_secondary));
        tv.setLineSpacing(dp(2), 1f);
        tv.setIncludeFontPadding(false);
        return tv;
    }

    private int getColorCompat(int res) {
        return getResources().getColor(res);
    }

    private int dp(float v) {
        return Math.round(getResources().getDisplayMetrics().density * v);
    }
}
