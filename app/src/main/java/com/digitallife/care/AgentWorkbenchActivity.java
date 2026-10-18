package com.digitallife.care;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Typeface;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

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
 * Agent 工作台：把 Agent **已经配置好的东西**集中看一眼，并就地做少量操作。
 *
 * <p>背景（v1.164.0）：v1.163.0 给 Agent 页底部 chips 接了「模型管理 ›」真跳转，
 * 但「工作流 / 定时」两类能力**只能让 AI 在对话里念一遍**——本机其实早把它们持久化
 * 在 {@code care_workflows} / {@code care_schedules} 两个 prefs 里（见 {@code CareTools}），
 * 用户没有任何界面能看到自己存了什么、什么时候会触发。v1.164.0 先补了「看」的入口。</p>
 *
 * <p>v1.166.0 起从「只看」变「可操作」：工作流可**立即运行**，定时任务可**暂停 / 启用**
 * （{@code enabled} 字段，由 {@code CareAutomation} 判定）。创建 / 编辑仍回到对话里让 AI
 * 编排（那是 Agent 的主场），这里只做「已配置的东西」的查看与轻操作。动作浏览已移到
 * 「动作池」页（其入口卡片在本页给出）。</p>
 */
public class AgentWorkbenchActivity extends Activity {

    /** 打开时定位到哪个小节；{@code null} = 顶部 */
    public static final String EXTRA_SECTION = "wb_section";
    public static final String SECTION_WORKFLOW = "workflow";
    public static final String SECTION_SCHEDULE = "schedule";

    /** 与 {@code CareTools} 中的常量保持一致（工作流 / 定时任务的落盘位置） */
    private static final String PREFS_WORKFLOW = "care_workflows";
    private static final String PREFS_SCHEDULE = "care_schedules";

    private static final int COLOR_PAUSED = 0xFFB08040;

    private LinearLayout body;
    private ScrollView scroll;
    private View wfHeader;
    private View scHeader;

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

        scroll = new ScrollView(this);
        scroll.setVerticalScrollBarEnabled(false);
        body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(dp(14), dp(4), dp(14), dp(16));
        scroll.addView(body, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        setContentView(root);

        refresh();

        // 从 Agent 页 chips 进来时定位到对应小节（布局完成后再滚，Top 才有值）
        String section = getIntent().getStringExtra(EXTRA_SECTION);
        View target = SECTION_WORKFLOW.equals(section) ? wfHeader
                : SECTION_SCHEDULE.equals(section) ? scHeader : null;
        if (target != null) {
            final View t = target;
            scroll.post(() -> scroll.smoothScrollTo(0, t.getTop()));
        }
    }

    /** 重建全部小节（暂停 / 启用、运行后刷新用） */
    private void refresh() {
        body.removeAllViews();
        buildModelsSection(body);
        wfHeader = buildWorkflowSection(body);
        scHeader = buildScheduleSection(body);
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

        // 跨模型动作池：把各模型的动作集中看（v1.165.0）
        Button pool = UiKit.secondaryButton(this, panel, "浏览跨模型动作池 ›");
        pool.setOnClickListener(v -> startActivity(new Intent(this, MotionPoolActivity.class)));
    }

    // ============ 工作流 ============

    private View buildWorkflowSection(LinearLayout body) {
        View header = UiKit.sectionTitle(this, body, "工作流", "Agent 编排的多步骤流程 · 可立即运行");
        LinearLayout panel = UiKit.panel(this, body);
        List<String[]> rows = readWorkflows();
        if (rows.isEmpty()) {
            panel.addView(hint("还没有工作流。在对话里说「帮我建一个工作流」即可创建。"));
        } else {
            for (String[] r : rows) {
                LinearLayout box = rowBox(r[0], r[1]);
                LinearLayout acts = actionRow();
                final String wf = r[0];
                chip(acts, "立即运行", R.color.brand_operit_light)
                        .setOnClickListener(v -> runWorkflowNow(wf, "工作流"));
                box.addView(acts);
                panel.addView(box);
            }
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
        View header = UiKit.sectionTitle(this, body, "定时任务", "按 cron 表达式周期触发工作流 · 可暂停");
        LinearLayout panel = UiKit.panel(this, body);
        List<String[]> rows = readSchedules();
        if (rows.isEmpty()) {
            panel.addView(hint("还没有定时任务。在对话里说「帮我建一个定时任务」即可创建。"));
        } else {
            for (String[] r : rows) {
                boolean enabled = "1".equals(r[2]);
                String subtitle = "cron " + r[1] + (enabled ? "" : "  ·  已暂停");
                LinearLayout box = rowBox(r[0], subtitle);
                if (!enabled) {
                    TextView t = (TextView) box.getChildAt(0);
                    t.setTextColor(COLOR_PAUSED);
                }
                LinearLayout acts = actionRow();
                final String taskName = r[0];
                final String wf = r[3];
                chip(acts, "立即运行", R.color.brand_operit_light)
                        .setOnClickListener(v -> runWorkflowNow(wf, "定时任务「" + taskName + "」"));
                chip(acts, enabled ? "暂停" : "启用", R.color.operit_text_secondary)
                        .setOnClickListener(v -> toggleSchedule(taskName));
                box.addView(acts);
                panel.addView(box);
            }
        }
        return header;
    }

    /** {name, "cron → workflow", enabled("1"/"0"), workflowName} */
    private List<String[]> readSchedules() {
        List<String[]> out = new ArrayList<>();
        try {
            Map<String, ?> all = getSharedPreferences(PREFS_SCHEDULE, MODE_PRIVATE).getAll();
            for (String name : all.keySet()) {
                String cron = "?";
                String workflow = "?";
                boolean enabled = true;
                try {
                    JSONObject o = new JSONObject(String.valueOf(all.get(name)));
                    cron = o.optString("cronExpr", "?");
                    workflow = o.optString("workflowName", "?");
                    enabled = o.optBoolean("enabled", true);
                } catch (Exception ignored) {
                }
                out.add(new String[]{name, cron + " → " + workflow, enabled ? "1" : "0", workflow});
            }
        } catch (Throwable ignored) {
        }
        Collections.sort(out, (a, b) -> a[0].compareTo(b[0]));
        return out;
    }

    // ============ 操作 ============

    /** 立即运行某个工作流（可能含网络 / 文件步骤，放后台线程，勿占主线程） */
    private void runWorkflowNow(String workflowName, String label) {
        if (workflowName == null || workflowName.isEmpty() || "?".equals(workflowName)) {
            toast("找不到对应的工作流");
            return;
        }
        toast("正在运行…");
        new Thread(() -> {
            String res;
            try {
                JSONObject args = new JSONObject();
                args.put("name", workflowName);
                res = new CareTools(getApplicationContext()).execute("run_workflow", args);
            } catch (Exception e) {
                res = "运行失败：" + UiKit.safeMsg(e);
            }
            final String out = res;
            runOnUiThread(() -> showResult(label + " 执行结果", out));
        }).start();
    }

    /** 暂停 / 启用一个定时任务（写回 {@code enabled} 字段） */
    private void toggleSchedule(String name) {
        SharedPreferences sp = getSharedPreferences(PREFS_SCHEDULE, MODE_PRIVATE);
        try {
            JSONObject o = new JSONObject(String.valueOf(sp.getString(name, "{}")));
            boolean enabled = o.optBoolean("enabled", true);
            o.put("enabled", !enabled);
            sp.edit().putString(name, o.toString()).apply();
            toast(enabled ? "已暂停「" + name + "」" : "已启用「" + name + "」");
            refresh();
        } catch (Exception e) {
            toast("操作失败：" + UiKit.safeMsg(e));
        }
    }

    private void showResult(String title, String text) {
        String body = text == null ? "（无输出）" : text;
        if (body.length() > 4000) body = body.substring(0, 4000) + "\n…（已截断）";
        new AlertDialog.Builder(this)
                .setTitle(title)
                .setMessage(body)
                .setPositiveButton("关闭", null)
                .show();
    }

    // ============ 行 / 空态 ============

    /** 一条记录：主标题 + 次级说明 */
    private LinearLayout rowBox(String title, String subtitle) {
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

    private LinearLayout actionRow() {
        LinearLayout acts = new LinearLayout(this);
        acts.setOrientation(LinearLayout.HORIZONTAL);
        acts.setGravity(Gravity.CENTER_VERTICAL);
        acts.setPadding(0, dp(6), 0, 0);
        return acts;
    }

    private TextView chip(LinearLayout row, String text, int colorRes) {
        TextView c = UiKit.chipButton(this, row, text, colorRes);
        ViewGroup.LayoutParams lp = c.getLayoutParams();
        if (lp instanceof LinearLayout.LayoutParams) {
            ((LinearLayout.LayoutParams) lp).rightMargin = dp(6);
        }
        return c;
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

    private void toast(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
    }

    private int getColorCompat(int res) {
        return getResources().getColor(res);
    }

    private int dp(float v) {
        return Math.round(getResources().getDisplayMetrics().density * v);
    }
}
