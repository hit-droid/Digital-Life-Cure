package com.digitallife.care;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Typeface;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
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
import java.util.Calendar;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
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
 * （{@code enabled} 字段，由 {@code CareAutomation} 判定）。v1.167.0 补齐管理闭环：工作流可
 * **查看步骤**（只读弹窗）与**删除**，定时任务可**删除**——删除走 {@code CareTools} 的
 * {@code delete_workflow} / {@code remove_scheduled_task} 工具（与对话里同一条语义），
 * 删除前二次确认。v1.168.0 让定时任务**看得懂、加得上**：行里把生 cron 翻成人话并给出
 * **下次触发**时间（{@code CronText}），并新增「＋ 新建定时任务」表单（选工作流 + cron 预设 /
 * 自定义 + 实时预览），复用 {@code add_scheduled_task}——定时任务是纯配置，不需要 AI 编排。
 * 工作流创建 / 编辑仍回到对话里让 AI 编排（那是 Agent 的主场），这里只做「已配置的东西」
 * 的查看与轻操作。动作浏览已移到「动作池」页（其入口卡片在本页给出）。</p>
 *
 * <p>执行可观测性（v1.173.0）：此前只有「管」没有「看」——定时任务会按 cron 真跑工作流，但结果
 * 不落盘，工作台看不到「上次几点跑、成没成」。本版给工作流 / 定时任务两行都补上「上次运行」
 * 一行（来自 {@link RunLog}，无记录显示「尚未运行」）与「记录」入口（回看最近 {@code 20} 次
 * 的时间 + 成败 + 结果摘要）。「立即运行」现在也会落盘并即时刷新；定时任务行的手动运行同时记
 * 到该任务与该工作流两条记录（{@code CareAutomation} 的自动触发同样双记录）。</p>
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
        View header = UiKit.sectionTitle(this, body, "工作流", "Agent 编排的多步骤流程 · 可查看 / 运行 / 删除");
        LinearLayout panel = UiKit.panel(this, body);
        List<String[]> rows = readWorkflows();
        if (rows.isEmpty()) {
            panel.addView(hint("还没有工作流。在对话里说「帮我建一个工作流」即可创建。"));
        } else {
            for (String[] r : rows) {
                LinearLayout box = rowBox(r[0], r[1]);
                LinearLayout acts = actionRow();
                final String wf = r[0];
                appendLastRun(box, RunLog.workflowKey(wf));
                chip(acts, "立即运行", R.color.brand_operit_light)
                        .setOnClickListener(v -> runWorkflowNow(wf, "工作流", RunLog.workflowKey(wf)));
                chip(acts, "记录", R.color.operit_text_secondary)
                        .setOnClickListener(v -> showRunLog(
                                RunLog.workflowKey(wf), "工作流「" + wf + "」记录"));
                chip(acts, "查看", R.color.operit_text_secondary)
                        .setOnClickListener(v -> showWorkflowSteps(wf));
                chip(acts, "删除", R.color.danger)
                        .setOnClickListener(v -> deleteWorkflow(wf));
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
        View header = UiKit.sectionTitle(this, body, "定时任务", "按 cron 周期触发工作流 · 可新建 / 暂停 / 删除");
        LinearLayout panel = UiKit.panel(this, body);

        LinearLayout addRow = actionRow();
        chip(addRow, "＋ 新建定时任务", R.color.brand_operit_light)
                .setOnClickListener(v -> promptNewSchedule());
        panel.addView(addRow);

        List<String[]> rows = readSchedules();
        if (rows.isEmpty()) {
            panel.addView(hint("还没有定时任务，点上方「＋ 新建定时任务」创建（需先有一个工作流）。"));
        } else {
            long now = System.currentTimeMillis();
            for (String[] r : rows) {
                boolean enabled = "1".equals(r[2]);
                String cron = r[1];
                long next = CronText.nextFireMillis(cron, now);
                String when = next > 0 ? "下次 " + formatWhen(next, now) : "近一年内不触发";
                RunLog.Record lastRun = RunLog.last(this, RunLog.scheduleKey(r[0]));
                String lastTxt = lastRun == null ? "尚未运行"
                        : (lastRun.ok ? "✅ " : "❌ ") + "上次运行 " + RunLog.whenText(lastRun.at, now);
                String subtitle = CronText.describe(cron)
                        + "\n" + cron + "  →  " + r[3]
                        + "  ·  " + (enabled ? when : "已暂停")
                        + "\n" + lastTxt;
                LinearLayout box = rowBox(r[0], subtitle);
                if (!enabled) {
                    TextView t = (TextView) box.getChildAt(0);
                    t.setTextColor(COLOR_PAUSED);
                }
                LinearLayout acts = actionRow();
                final String taskName = r[0];
                final String wf = r[3];
                chip(acts, "立即运行", R.color.brand_operit_light)
                        .setOnClickListener(v -> runWorkflowNow(wf, "定时任务「" + taskName + "」",
                                RunLog.scheduleKey(taskName), RunLog.workflowKey(wf)));
                chip(acts, "记录", R.color.operit_text_secondary)
                        .setOnClickListener(v -> showRunLog(
                                RunLog.scheduleKey(taskName), "定时任务「" + taskName + "」记录"));
                chip(acts, enabled ? "暂停" : "启用", R.color.operit_text_secondary)
                        .setOnClickListener(v -> toggleSchedule(taskName));
                chip(acts, "删除", R.color.danger)
                        .setOnClickListener(v -> deleteSchedule(taskName));
                box.addView(acts);
                panel.addView(box);
            }
        }
        return header;
    }

    /** {name, cron, enabled("1"/"0"), workflowName} */
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
                out.add(new String[]{name, cron, enabled ? "1" : "0", workflow});
            }
        } catch (Throwable ignored) {
        }
        Collections.sort(out, (a, b) -> a[0].compareTo(b[0]));
        return out;
    }

    // ============ 操作 ============

    /**
     * 立即运行某个工作流（可能含网络 / 文件步骤，放后台线程，勿占主线程）。
     * 运行结果落盘到 {@code logKeys} 指定的执行记录（工作流行记 workflow、定时任务行另记 schedule），
     * 完成后刷新界面让「上次运行」立即更新。
     */
    private void runWorkflowNow(String workflowName, String label, String... logKeys) {
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
            boolean ok = RunLog.looksOk(res);
            for (String k : logKeys) RunLog.record(getApplicationContext(), k, ok, res);
            final String out = res;
            runOnUiThread(() -> {
                showResult(label + " 执行结果", out);
                refresh();
            });
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

    // ============ 新建定时任务 ============

    /** 新建定时任务表单：先选工作流（工作流创建仍归对话，这里只做纯配置的挂载） */
    private void promptNewSchedule() {
        List<String[]> wfs = readWorkflows();
        if (wfs.isEmpty()) {
            toast("还没有工作流——先在对话里让 Agent 建一个工作流，再回来加定时任务。");
            return;
        }
        if (wfs.size() == 1) {
            showScheduleForm(wfs.get(0)[0]);
            return;
        }
        String[] names = new String[wfs.size()];
        for (int i = 0; i < wfs.size(); i++) names[i] = wfs.get(i)[0];
        new AlertDialog.Builder(this)
                .setTitle("定时触发哪个工作流？")
                .setItems(names, (d, w) -> showScheduleForm(names[w]))
                .setNegativeButton("取消", null)
                .show();
    }

    /** 表单：任务名 + cron（预设 / 自定义）+ 实时预览（人话 + 下次触发） */
    private void showScheduleForm(String workflow) {
        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        form.setPadding(dp(18), dp(6), dp(18), dp(6));

        EditText etName = UiKit.input(this, form, "任务名称（如：每日晨报）", "");

        TextView wfLabel = new TextView(this);
        wfLabel.setText("工作流：" + workflow);
        wfLabel.setTextSize(13f);
        wfLabel.setTextColor(getColorCompat(R.color.operit_text_secondary));
        wfLabel.setIncludeFontPadding(false);
        LinearLayout.LayoutParams wlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        wlp.topMargin = dp(8);
        form.addView(wfLabel, wlp);

        EditText etCron = UiKit.input(this, form, "cron（分 时 日 月 周）", "0 9 * * *");

        // cron 预设胶囊
        HorizontalScrollView hsv = new HorizontalScrollView(this);
        hsv.setHorizontalScrollBarEnabled(false);
        LinearLayout presets = new LinearLayout(this);
        presets.setOrientation(LinearLayout.HORIZONTAL);
        presets.setPadding(0, dp(4), 0, dp(4));
        hsv.addView(presets);
        String[][] ps = {
                {"每天 09:00", "0 9 * * *"},
                {"每天 20:00", "0 20 * * *"},
                {"每周一 09:00", "0 9 * * 1"},
                {"每小时", "0 * * * *"},
                {"每 30 分钟", "*/30 * * * *"},
        };
        for (String[] pr : ps) {
            TextView p = UiKit.chipButton(this, presets, pr[0], R.color.operit_text_secondary);
            LinearLayout.LayoutParams plp = (LinearLayout.LayoutParams) p.getLayoutParams();
            plp.rightMargin = dp(6);
            p.setOnClickListener(v -> etCron.setText(pr[1]));
        }
        form.addView(hsv);

        TextView preview = new TextView(this);
        preview.setTextSize(12f);
        preview.setTextColor(getColorCompat(R.color.operit_accent));
        preview.setLineSpacing(dp(2), 1f);
        preview.setIncludeFontPadding(false);
        LinearLayout.LayoutParams vlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        vlp.topMargin = dp(6);
        form.addView(preview, vlp);

        final Runnable updatePreview = () -> preview.setText(previewCron(etCron.getText().toString()));
        etCron.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void afterTextChanged(Editable s) { updatePreview.run(); }
        });
        updatePreview.run();

        ScrollView wrap = new ScrollView(this);
        wrap.addView(form);

        final AlertDialog dlg = new AlertDialog.Builder(this)
                .setTitle("新建定时任务")
                .setView(wrap)
                .setNegativeButton("取消", null)
                .setPositiveButton("创建", null)
                .create();
        // 自定义确定键：校验失败时留在弹窗内（默认行为会直接掉）
        dlg.setOnShowListener(x -> dlg.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String name = etName.getText().toString().trim();
            String cron = etCron.getText().toString().trim();
            if (name.isEmpty()) {
                toast("请填写任务名称");
                return;
            }
            if (!CronText.isValid(cron)) {
                toast("cron 不合法：需 5 个字段（分 时 日 月 周）");
                return;
            }
            dlg.dismiss();
            createSchedule(name, cron, workflow);
        }));
        dlg.show();
    }

    /** cron 预览文案：人话 + 下次触发（非法给出可读提示） */
    private String previewCron(String cron) {
        if (!CronText.isValid(cron)) {
            return "cron 不合法（应为 5 个字段：分 时 日 月 周）";
        }
        long now = System.currentTimeMillis();
        long next = CronText.nextFireMillis(cron, now);
        String when = next > 0 ? "下次触发：" + formatWhen(next, now) : "近一年内不会触发";
        return "将按「" + CronText.describe(cron) + "」触发　" + when;
    }

    private void createSchedule(String name, String cron, String workflow) {
        if (getSharedPreferences(PREFS_SCHEDULE, MODE_PRIVATE).contains(name)) {
            new AlertDialog.Builder(this)
                    .setTitle("任务已存在")
                    .setMessage("已存在同名任务「" + name + "」，继续将覆盖它的 cron 与工作流。")
                    .setNegativeButton("取消", null)
                    .setPositiveButton("覆盖", (d, w) -> doCreateSchedule(name, cron, workflow))
                    .show();
            return;
        }
        doCreateSchedule(name, cron, workflow);
    }

    /** 后台调用 CareTools.add_scheduled_task（与对话同一条语义），回主线程提示并刷新 */
    private void doCreateSchedule(String name, String cron, String workflow) {
        new Thread(() -> {
            String res;
            try {
                JSONObject args = new JSONObject();
                args.put("name", name);
                args.put("cronExpr", cron);
                args.put("workflowName", workflow);
                res = new CareTools(getApplicationContext()).execute("add_scheduled_task", args);
            } catch (Exception e) {
                res = "创建失败：" + UiKit.safeMsg(e);
            }
            final String out = res;
            runOnUiThread(() -> {
                toast(out);
                refresh();
            });
        }).start();
    }

    /** 把绝对时间说成人话：今天 / 明天 / M月d日 + HH:mm */
    private String formatWhen(long millis, long now) {
        Calendar a = Calendar.getInstance();
        a.setTimeInMillis(now);
        Calendar b = Calendar.getInstance();
        b.setTimeInMillis(millis);
        String hm = String.format(Locale.ROOT, "%02d:%02d",
                b.get(Calendar.HOUR_OF_DAY), b.get(Calendar.MINUTE));
        int dayDiff = b.get(Calendar.DAY_OF_YEAR) - a.get(Calendar.DAY_OF_YEAR);
        if (dayDiff == 0 && b.get(Calendar.YEAR) == a.get(Calendar.YEAR)) return "今天 " + hm;
        if (dayDiff == 1 && b.get(Calendar.YEAR) == a.get(Calendar.YEAR)) return "明天 " + hm;
        return (b.get(Calendar.MONTH) + 1) + "月" + b.get(Calendar.DAY_OF_MONTH) + "日 " + hm;
    }

    /** 查看某工作流的步骤清单（只读，不执行）——此前「跑起来才知道里面是什么」 */
    private void showWorkflowSteps(String name) {
        String raw = getSharedPreferences(PREFS_WORKFLOW, MODE_PRIVATE).getString(name, null);
        if (raw == null) {
            toast("找不到该工作流");
            return;
        }
        StringBuilder sb = new StringBuilder();
        try {
            JSONArray steps = new JSONObject(raw).optJSONArray("steps");
            if (steps == null || steps.length() == 0) {
                sb.append("（没有步骤）");
            } else {
                for (int i = 0; i < steps.length(); i++) {
                    JSONObject step = steps.optJSONObject(i);
                    if (step == null) continue;
                    JSONObject args = step.optJSONObject("args");
                    sb.append(i + 1).append(". ").append(step.optString("tool", "?"));
                    if (args != null && args.length() > 0) sb.append("  ").append(args);
                    sb.append('\n');
                }
            }
        } catch (Exception e) {
            sb.append("步骤解析失败：").append(UiKit.safeMsg(e));
        }
        String text = sb.toString().trim();
        if (text.length() > 4000) text = text.substring(0, 4000) + "\n…（已截断）";
        new AlertDialog.Builder(this)
                .setTitle("工作流「" + name + "」步骤")
                .setMessage(text)
                .setPositiveButton("关闭", null)
                .show();
    }

    private void deleteWorkflow(String name) {
        confirmDelete("删除工作流",
                "确定删除「" + name + "」？删除后不可恢复；引用它的定时任务将无法执行。",
                "delete_workflow", name);
    }

    private void deleteSchedule(String name) {
        confirmDelete("删除定时任务",
                "确定删除「" + name + "」？删除后不再按 cron 触发（已执行的工作流不受影响）。",
                "remove_scheduled_task", name);
    }

    /** 删除类操作统一二次确认 */
    private void confirmDelete(String title, String message, String tool, String name) {
        new AlertDialog.Builder(this)
                .setTitle(title)
                .setMessage(message)
                .setNegativeButton("取消", null)
                .setPositiveButton("删除", (d, w) -> runToolThenRefresh(tool, name))
                .show();
    }

    /** 后台调用 CareTools 的删除工具（复用其语义与提示），回主线程提示并刷新 */
    private void runToolThenRefresh(String tool, String name) {
        new Thread(() -> {
            String res;
            try {
                JSONObject args = new JSONObject();
                args.put("name", name);
                res = new CareTools(getApplicationContext()).execute(tool, args);
            } catch (Exception e) {
                res = "删除失败：" + UiKit.safeMsg(e);
            }
            final String out = res;
            runOnUiThread(() -> {
                toast(out);
                refresh();
            });
        }).start();
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

    // ============ 执行记录（v1.173.0） ============

    /** 在记录框里补一行「上次运行」（无记录则「尚未运行」），让「管」与「看」在同一行可见 */
    private void appendLastRun(LinearLayout box, String key) {
        String line = RunLog.lastLine(this, key, System.currentTimeMillis());
        TextView tv = new TextView(this);
        tv.setText(line != null ? line : "尚未运行");
        tv.setTextSize(11.5f);
        tv.setTextColor(getColorCompat(line != null
                ? R.color.operit_text_secondary : R.color.operit_text_hint));
        tv.setIncludeFontPadding(false);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(2);
        box.addView(tv, lp);
    }

    /** 查看某个 key 的最近执行记录（最新在前） */
    private void showRunLog(String key, String title) {
        List<RunLog.Record> runs = RunLog.recent(this, key);
        StringBuilder sb = new StringBuilder();
        if (runs.isEmpty()) {
            sb.append("还没有执行记录。\n运行一次后这里会显示时间、成败与结果摘要。");
        } else {
            long now = System.currentTimeMillis();
            for (RunLog.Record r : runs) {
                sb.append(r.ok ? "✅ " : "❌ ").append(RunLog.whenText(r.at, now)).append('\n');
                if (!r.summary.isEmpty()) sb.append("   ").append(r.summary).append('\n');
                sb.append('\n');
            }
        }
        String text = sb.toString().trim();
        if (text.length() > 4000) text = text.substring(0, 4000) + "\n…（已截断）";
        new AlertDialog.Builder(this)
                .setTitle(title)
                .setMessage(text)
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
