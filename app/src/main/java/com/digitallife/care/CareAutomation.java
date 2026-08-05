package com.digitallife.care;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import com.digitallife.render.Live2DNative;

import org.json.JSONObject;

import java.util.Calendar;
import java.util.Map;

/**
 * CareAutomation：护理大脑自动化引擎。
 *
 * 让护理大脑「真正自动」：
 *  - 自动体检：周期检查所有模型完整性，发现问题主动气泡报告
 *  - 自动修复：体检发现的异常（缺 model3.json 等）自动调用修复工具
 *  - 定时任务：按 cron 表达式周期执行对应工作流（真正执行，不只存配置）
 *  - 自动记录：所有自动动作写入 CareAI 上下文，让 AI 后续能主动反馈
 *
 * 由 PetService 在桌宠启动时创建并 start()。
 */
public class CareAutomation {

    /** 渲染反馈桥：把自动动作的结果展示给用户 */
    public interface Report {
        void onAutoReport(String text);
    }

    private static final long HEALTH_INTERVAL_MS = 30 * 60 * 1000L;   // 每 30 分钟体检一次
    private static final long HEALTH_FIRST_DELAY_MS = 8 * 1000L;      // 启动后 8 秒首次体检
    private static final long CRON_CHECK_INTERVAL_MS = 30 * 1000L;    // 每 30 秒检查一次定时任务

    private final Context ctx;
    private final CareExecutor executor;
    private final CareAI careAI;
    private final Report report;
    private final Handler handler;
    private boolean running = false;

    public CareAutomation(Context ctx, CareExecutor executor, CareAI careAI, Report report) {
        this.ctx = ctx.getApplicationContext();
        this.executor = executor;
        this.careAI = careAI;
        this.report = report;
        this.handler = new Handler(Looper.getMainLooper());
    }

    /** 启动自动化引擎 */
    public void start() {
        if (running) return;
        running = true;
        handler.postDelayed(healthTask, HEALTH_FIRST_DELAY_MS);
        handler.postDelayed(cronTask, CRON_CHECK_INTERVAL_MS);
    }

    public void stop() {
        running = false;
        handler.removeCallbacks(healthTask);
        handler.removeCallbacks(cronTask);
    }

    // ============ 自动体检 + 自动修复 ============

    private final Runnable healthTask = new Runnable() {
        @Override
        public void run() {
            if (!running) return;
            try {
                runHealthCheck();
            } catch (Exception e) {
                if (report != null) report.onAutoReport("体检出错: " + e.getMessage());
            }
            handler.postDelayed(this, HEALTH_INTERVAL_MS);
        }
    };

    /** 执行一次完整体检：列出模型 → 逐个分析 → 异常自动修复 → 汇总报告 */
    public void runHealthCheck() {
        int count = 0;
        try {
            count = Live2DNative.nativeGetModelCount();
        } catch (Throwable ignored) {
        }
        if (count <= 0) {
            return; // 还没装模型，无需打扰
        }

        StringBuilder sb = new StringBuilder("🩺 自动体检（" + count + " 个模型）:\n");
        int problems = 0;
        for (int i = 0; i < count; i++) {
            String dirName = null;
            try {
                dirName = Live2DNative.nativeGetModelDirName(i);
            } catch (Throwable ignored) {
            }
            if (dirName == null || dirName.isEmpty()) continue;
            String analysis = executor.analyzeModel(dirName);
            if (analysis == null) continue;
            boolean hasProblem = analysis.contains("⚠") || analysis.contains("❌") || analysis.contains("缺失");
            sb.append("· ").append(dirName).append(hasProblem ? " ⚠ 有问题" : " ✅ 正常").append("\n");
            if (hasProblem) {
                problems++;
                // 自动修复
                String fix = executor.repairModel(dirName);
                if (fix.contains("✅")) {
                    sb.append("    → 已自动修复\n");
                } else {
                    sb.append("    → 需要人工处理\n");
                }
            }
        }
        if (problems > 0) {
            sb.append("共发现 ").append(problems).append(" 个异常模型。");
            careAI.logAutomationEvent(sb.toString());
            if (report != null) report.onAutoReport(sb.toString());
        }
    }

    // ============ 定时任务（cron 真正执行） ============

    private final Runnable cronTask = new Runnable() {
        @Override
        public void run() {
            if (!running) return;
            try {
                checkScheduledTasks();
            } catch (Exception ignored) {
            }
            handler.postDelayed(this, CRON_CHECK_INTERVAL_MS);
        }
    };

    /** 检查当前时间是否命中某个定时任务的 cron 表达式，命中则执行其工作流 */
    private void checkScheduledTasks() {
        Map<String, ?> all = null;
        try {
            all = ctx.getSharedPreferences("care_schedules", Context.MODE_PRIVATE).getAll();
        } catch (Exception ignored) {
        }
        if (all == null || all.isEmpty()) return;

        Calendar cal = Calendar.getInstance();
        int minute = cal.get(Calendar.MINUTE);
        int hour = cal.get(Calendar.HOUR_OF_DAY);
        int day = cal.get(Calendar.DAY_OF_MONTH);
        int month = cal.get(Calendar.MONTH) + 1;
        int dow = cal.get(Calendar.DAY_OF_WEEK); // 1=周日

        for (String name : all.keySet()) {
            try {
                JSONObject task = new JSONObject((String) all.get(name));
                String cron = task.optString("cronExpr", "");
                String workflowName = task.optString("workflowName", "");
                if (cron.isEmpty() || workflowName.isEmpty()) continue;
                if (!cronMatches(cron, minute, hour, day, month, dow)) continue;

                // 命中：执行工作流
                String result;
                try {
                    JSONObject args = new JSONObject();
                    args.put("name", workflowName);
                    result = executor.getTools().execute("run_workflow", args);
                } catch (Exception e) {
                    result = "执行失败: " + e.getMessage();
                }
                careAI.logAutomationEvent("定时任务「" + name + "」触发，执行工作流「" + workflowName + "」:\n" + result);
                if (report != null) report.onAutoReport("⏰ 定时任务「" + name + "」已触发\n" + result);
            } catch (Exception ignored) {
            }
        }
    }

    /** 简化 cron 匹配：支持 分 时 日 月 周，字段支持 *、步进、具体值、a-b 范围 */
    static boolean cronMatches(String cron, int minute, int hour, int day, int month, int dow) {
        if (cron == null) return false;
        String[] parts = cron.trim().split("\\s+");
        if (parts.length < 5) return false;
        try {
            if (!fieldMatch(parts[0], minute)) return false;
            if (!fieldMatch(parts[1], hour)) return false;
            if (!parts[2].equals("*") && !fieldMatch(parts[2], day)) return false;
            if (!parts[3].equals("*") && !fieldMatch(parts[3], month)) return false;
            if (!parts[4].equals("*") && !fieldMatch(parts[4], dow)) return false;
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private static boolean fieldMatch(String field, int value) throws Exception {
        if (field.equals("*")) return true;
        // 支持逗号列表
        if (field.contains(",")) {
            for (String f : field.split(",")) {
                if (fieldMatch(f.trim(), value)) return true;
            }
            return false;
        }
        // 步进 */n
        if (field.startsWith("*/")) {
            int step = Integer.parseInt(field.substring(2));
            return value % step == 0;
        }
        // 范围 a-b
        if (field.contains("-")) {
            String[] range = field.split("-");
            int lo = Integer.parseInt(range[0].trim());
            int hi = Integer.parseInt(range[1].trim());
            return value >= lo && value <= hi;
        }
        // 具体值
        return Integer.parseInt(field.trim()) == value;
    }
}
