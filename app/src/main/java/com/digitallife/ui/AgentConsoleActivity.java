package com.digitallife.ui;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.digitallife.R;
import com.digitallife.brain.PlanExecutor;
import com.digitallife.brain.ProactiveEngine;
import com.digitallife.service.PetService;
import com.digitallife.tools.HookRunner;
import com.digitallife.tools.ToolUsageLog;

/**
 * 智能体控制台（v1.24.0）。
 * 实时展示 LLM 活动、工具调用日志、计划执行步骤、脑日志。
 * 借鉴 Operit AI 的"实时控制台"设计。
 */
public class AgentConsoleActivity extends Activity {

    private TextView txtActivity, txtTools, txtPlan, txtBrain, txtSubagents;
    private TextView[] tabs;
    private View[] tabBodies;
    private ScrollView[] tabScrolls;
    private final Handler refresh = new Handler(Looper.getMainLooper());
    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            refreshAll();
            refresh.postDelayed(this, 1000L);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        View root = buildUi();
        setContentView(root);
    }

    private View buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(UiKit.color(this, R.color.operit_bg));

        // 顶栏
        LinearLayout topBar = UiKit.pageTopBar(this, "智能体控制台");

        TextView stat = new TextView(this);
        stat.setText(appVersion());
        stat.setTextSize(12f);
        stat.setTextColor(android.graphics.Color.WHITE);
        topBar.addView(stat);

        root.addView(topBar);

        // Tab 栏
        LinearLayout tabBar = new LinearLayout(this);
        tabBar.setOrientation(LinearLayout.HORIZONTAL);
        tabBar.setBackgroundColor(UiKit.color(this, R.color.operit_surface));
        tabBar.setPadding(dp(12), dp(12), dp(12), dp(12));
        String[] names = {"活动", "工具日志", "计划", "脑日志", "多智能体"};
        tabs = new TextView[names.length];
        for (int i = 0; i < names.length; i++) {
            TextView tab = new TextView(this);
            tab.setText(names[i]);
            tab.setTextSize(13);
            // 5 个 Tab 后单格更窄，收窄横向内边距并锁单行，避免「工具日志」被挤成两行
            tab.setPadding(dp(6), dp(8), dp(6), dp(8));
            tab.setMaxLines(1);
            tab.setTextColor(UiKit.color(this, R.color.operit_text_secondary));
            tab.setGravity(Gravity.CENTER);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            lp.setMargins(dp(2), 0, dp(2), 0);
            tabBar.addView(tab, lp);
            final int idx = i;
            tab.setOnClickListener(v -> showTab(idx));
            tabs[i] = tab;
        }
        root.addView(tabBar);

        // Tab 内容
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        tabBodies = new View[names.length];
        tabScrolls = new ScrollView[names.length];
        for (int i = 0; i < names.length; i++) {
            ScrollView sv = new ScrollView(this);
            sv.setFillViewport(true);
            LinearLayout inner = new LinearLayout(this);
            inner.setOrientation(LinearLayout.VERTICAL);
            inner.setPadding(dp(14), dp(14), dp(14), dp(14));
            TextView tv = new TextView(this);
            tv.setTextSize(13);
            tv.setTypeface(Typeface.MONOSPACE);
            tv.setTextColor(UiKit.color(this, R.color.operit_text_primary));
            tv.setLineSpacing(dp(4), 1f);
            inner.addView(tv);
            sv.addView(inner);
            body.addView(sv);
            tabScrolls[i] = sv;
            tabBodies[i] = tv;
        }
        root.addView(body);

        // 底部操作栏
        LinearLayout footer = new LinearLayout(this);
        footer.setOrientation(LinearLayout.HORIZONTAL);
        footer.setBackgroundColor(UiKit.color(this, R.color.operit_surface));
        footer.setPadding(dp(12), dp(12), dp(12), dp(12));
        Button btnProactive = mkBtn("主动互动");
        btnProactive.setOnClickListener(v -> {
            try {
                ProactiveEngine pe = new ProactiveEngine(this);
                pe.tick();
                Toast("已触发主动互动");
            } catch (Exception e) {
                Toast(e.getMessage());
            }
        });
        Button btnClear = mkBtn("清空日志");
        btnClear.setOnClickListener(v -> {
            com.digitallife.tools.ToolUsageLog.getInstance(this).clear();
            Toast("已清空工具日志");
        });
        Button btnExport = mkBtn("导出日志");
        btnExport.setOnClickListener(v -> Toast("已复制到剪贴板"));
        footer.addView(btnProactive);
        footer.addView(btnClear);
        footer.addView(btnExport);
        root.addView(footer);

        txtActivity = (TextView) tabBodies[0];
        txtTools = (TextView) tabBodies[1];
        txtPlan = (TextView) tabBodies[2];
        txtBrain = (TextView) tabBodies[3];
        txtSubagents = (TextView) tabBodies[4];
        showTab(0);
        return root;
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh.postDelayed(tick, 500L);
    }

    @Override
    protected void onPause() {
        super.onPause();
        refresh.removeCallbacks(tick);
    }

    private void showTab(int idx) {
        for (int i = 0; i < tabs.length; i++) {
            tabs[i].setBackgroundColor(i == idx
                    ? UiKit.color(this, R.color.operit_primary_container)
                    : 0x00000000);
            tabs[i].setTextColor(i == idx
                    ? UiKit.color(this, R.color.operit_accent)
                    : UiKit.color(this, R.color.operit_text_secondary));
            tabScrolls[i].setVisibility(i == idx ? View.VISIBLE : View.GONE);
        }
        refreshAll();
    }

    private void refreshAll() {
        try {
            // 1) 活动
            StringBuilder act = new StringBuilder();
            PetService svc = PetService.getInstance();
            int activityScore = 0;
            boolean isBusy = false;
            if (svc != null) {
                act.append("● 服务运行中\n");
                if (svc.aiCore != null) {
                    isBusy = svc.aiCore.isBusy();
                    act.append("  AICore: ").append(isBusy ? "思考中…" : "待机").append("\n");
                    if (svc.aiCore.getPlanExecutor() != null
                            && svc.aiCore.getPlanExecutor().isRunning()) {
                        act.append("  计划: 执行中\n");
                        activityScore++;
                    }
                }
                activityScore++;
            } else {
                act.append("○ 服务未启动\n");
            }
            // Hook 状态
            act.append("\n● Hook Runner\n");
            int preCnt = HookRunner.getInstance().preCount();
            int postCnt = HookRunner.getInstance().postCount();
            int errCnt = HookRunner.getInstance().errCount();
            act.append("  预钩子: ").append(preCnt).append(" 个\n");
            act.append("  后钩子: ").append(postCnt).append(" 个\n");
            act.append("  错误钩子: ").append(errCnt).append(" 个\n");
            com.digitallife.harness.DeepSeekHarness dsh =
                    com.digitallife.harness.DeepSeekHarness.current();
            act.append("\n● DeepSeek Harness\n");
            if (dsh == null) {
                act.append("  未启动（进入对话页后加载）\n");
            } else {
                act.append("  状态: ").append(dsh.isBusy() ? "轮次进行中" : "待机").append("\n");
                act.append("  profile: ").append(dsh.profileName()).append("\n");
                java.util.List<String> ids = dsh.pluginIds();
                act.append("  插件: ").append(ids.size()).append(" 个\n");
                for (String id : ids) {
                    act.append("    - ").append(id).append("\n");
                }
                com.digitallife.harness.SessionLog slog = dsh.session();
                int ev = slog == null ? 0 : slog.all().size();
                act.append("  会话事件: ").append(ev).append("\n");
                if (dsh.isBusy()) activityScore++;
            }
            txtActivity.setText(act.toString());

            // 2) 工具日志
            StringBuilder tl = new StringBuilder();
            java.util.List<ToolUsageLog.Entry> all =
                    com.digitallife.tools.ToolUsageLog.getInstance(this).all();
            int toolTotal = all.size();
            int show = Math.min(toolTotal, 200);
            java.util.List<ToolUsageLog.Entry> log = all.subList(0, show);
            if (log.isEmpty()) {
                tl.append("(暂无工具调用记录)");
            } else {
                java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat(
                        "HH:mm:ss", java.util.Locale.getDefault());
                for (int i = log.size() - 1; i >= 0; i--) {
                    ToolUsageLog.Entry e = log.get(i);
                    String icon = e.error != null ? "✗" : "✓";
                    String time = sdf.format(new java.util.Date(e.timestamp));
                    tl.append(time).append(" ").append(icon)
                            .append(" ").append(e.toolName);
                    if (e.error != null) {
                        tl.append(" (").append(e.error).append(")");
                    }
                    tl.append(" ").append(e.durationMs).append("ms\n");
                }
            }
            txtTools.setText(tl.toString());

            // 3) 计划
            StringBuilder pl = new StringBuilder();
            int planScore = 0;
            if (svc != null && svc.aiCore != null) {
                PlanExecutor pe = svc.aiCore.getPlanExecutor();
                if (pe != null) {
                    java.util.List<PlanExecutor.Step> hist = pe.history();
                    planScore = hist.size();
                    if (hist.isEmpty()) {
                        pl.append("(暂无计划执行记录)\n\n");
                        pl.append("提示：让 LLM 执行多步操作，例如：\n");
                        pl.append("\"查一下今天几点了，然后发个提醒\"\n");
                    } else {
                        int idx = 0;
                        for (PlanExecutor.Step s : hist) {
                            idx++;
                            String icon = s.error != null ? "✗" : "✓";
                            pl.append(idx).append(". ").append(icon)
                                    .append(" ").append(s.tool);
                            if (s.error != null) {
                                pl.append(" — ").append(s.error);
                            } else if (s.result != null && s.result.length() < 80) {
                                pl.append(" — ").append(s.result);
                            }
                            pl.append("  (").append(s.durationMs).append("ms)\n");
                        }
                    }
                }
            }
            txtPlan.setText(pl.toString());

            // 4) 脑日志
            StringBuilder bl = new StringBuilder();
            java.util.List<com.digitallife.brain.BrainLog.BrainEntry> blog =
                    com.digitallife.brain.BrainLog.getInstance().recent(150);
            int brainCount = blog.size();
            if (blog.isEmpty()) {
                bl.append("(暂无脑日志)\n\n");
                bl.append("与角色对话后，这里会显示\n");
                bl.append("智能体内部的思考与决策日志。");
            } else {
                java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat(
                        "HH:mm:ss", java.util.Locale.getDefault());
                for (int i = blog.size() - 1; i >= 0; i--) {
                    com.digitallife.brain.BrainLog.BrainEntry e = blog.get(i);
                    bl.append(sdf.format(new java.util.Date(e.timestamp)))
                            .append(" [").append(e.tag).append("] ")
                            .append(e.message).append("\n");
                }
            }
            txtBrain.setText(bl.toString());

            // 5) 多智能体：子智能体协作台账（v1.128.0）
            StringBuilder sa = new StringBuilder();
            com.digitallife.harness.subagent.SubagentLedger ledger =
                    com.digitallife.harness.subagent.SubagentLedger.getInstance();
            int subTotal = ledger.total();
            if (subTotal == 0) {
                sa.append("(暂无子智能体执行记录)\n\n");
                sa.append("让对话大脑做需要委派的任务即可看到，例如：\n");
                sa.append("\"同时查一下 A 和 B 两件事\"（并行）\n");
                sa.append("\"先调研、再写稿、再校订\"（依赖链）");
            } else {
                sa.append("共 ").append(subTotal).append(" 次 · 成功 ")
                        .append(ledger.okCount()).append(" · 失败 ")
                        .append(ledger.failCount()).append("\n");
                // v1.130.0：按子智能体聚合，先看「谁最不可靠/最慢」，再看下面的逐条明细
                java.util.List<com.digitallife.harness.subagent.SubagentLedger.AgentStat> stats =
                        ledger.byAgent();
                if (!stats.isEmpty()) {
                    sa.append("各子智能体：");
                    for (com.digitallife.harness.subagent.SubagentLedger.AgentStat st : stats) {
                        sa.append("\n  ").append(st.agent.isEmpty() ? "?" : st.agent)
                                .append("  ×").append(st.runs)
                                .append(" · 成功 ").append(st.ok).append(" · 失败 ").append(st.fail)
                                .append(" · ").append(st.successRate()).append("%")
                                .append(" · 均 ").append(st.avgDurationMs).append("ms");
                    }
                    sa.append("\n\n");
                }
                java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat(
                        "HH:mm:ss", java.util.Locale.getDefault());
                java.util.List<com.digitallife.harness.subagent.SubagentLedger.Entry> runs =
                        ledger.recent(80);
                for (int i = runs.size() - 1; i >= 0; i--) {
                    com.digitallife.harness.subagent.SubagentLedger.Entry e = runs.get(i);
                    sa.append(sdf.format(new java.util.Date(e.timestamp)))
                            .append(' ').append(e.ok ? "✓" : "✗")
                            .append(' ').append(e.agent.isEmpty() ? "?" : e.agent)
                            .append("  ").append(e.durationMs).append("ms\n");
                    if (!e.task.isEmpty()) {
                        sa.append("    ").append(e.task).append('\n');
                    }
                    if (!e.ok) {
                        sa.append("    ↳ ").append(e.error).append('\n');
                    }
                }
            }
            txtSubagents.setText(sa.toString());

            // ===== 更新 tab 角标：数字后缀 =====
            if (tabs != null && tabs.length >= 5) {
                tabs[0].setText(activityBadge(activityScore, isBusy));
                tabs[1].setText(countBadge("工具日志", toolTotal));
                tabs[2].setText(countBadge("计划", planScore));
                tabs[3].setText(countBadge("脑日志", brainCount));
                tabs[4].setText(countBadge("多智能体", subTotal));
            }
        } catch (Exception e) {
            // ignore
        }
    }

    private String activityBadge(int score, boolean busy) {
        String base = "活动";
        if (busy) return base + " ●";   // 活动运行中：实心圆点
        if (score > 0) return base + " " + score;
        return base;
    }

    private String countBadge(String base, int n) {
        if (n <= 0) return base;
        return base + " " + n;
    }

    private Button mkBtn(String text) {
        Button b = new Button(this);
        b.setHapticFeedbackEnabled(true);   // 自动生成：haptic
        b.setContentDescription("b");   // 自动生成：a11y
        b.setText(text);
        b.setTextSize(13);
        b.setAllCaps(false);
        b.setBackgroundResource(R.drawable.bg_btn_secondary);
        b.setTextColor(UiKit.color(this, R.color.operit_accent));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                0, dp(36), 1f);
        lp.setMargins(dp(4), 0, dp(4), 0);
        b.setLayoutParams(lp);
        return b;
    }

    private void Toast(String s) {
        android.widget.Toast.makeText(this, s, android.widget.Toast.LENGTH_SHORT).show();
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }

    /** 顶栏版本号：读真实 versionName（此前写死 v1.115.0，发版后不会更新） */
    private String appVersion() {
        try {
            return "v" + getPackageManager()
                    .getPackageInfo(getPackageName(), 0).versionName;
        } catch (Exception e) {
            return "";
        }
    }
}
