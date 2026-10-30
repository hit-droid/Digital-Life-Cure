package com.digitallife.care;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import com.digitallife.render.Live2DNative;

import org.json.JSONObject;

import java.util.Calendar;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

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
    /** 定时任务去重：taskName -> 最近一次触发的分钟键（yyyy-M-d H:m） */
    private final Map<String, String> lastFired = new HashMap<>();
    /** v1.178.0：定时连招播放器（静默播放，结果经 Report 反馈） */
    private final ComboPlayer comboPlayer;
    /**
     * v1.191.0：体检与定时工作流的后台执行器。
     *
     * <p>此前 {@code runHealthCheck()} 与 {@code checkScheduledTasks()} 都在主线程 Handler 上跑，
     * 而它们内部是纯磁盘 IO：逐个模型 {@code analyze_model} / {@code repair_model}、执行整条
     * {@code run_workflow}（可能解压模型、写文件）。模型多 / 任务重时单次可达数秒 → 直接 ANR。</p>
     *
     * <p>改为：Handler 只负责「按时唤醒」，实际工作丢给这个单线程后台执行器（串行，避免并发体检）。</p>
     */
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "care-automation");
        t.setDaemon(true);
        return t;
    });

    public CareAutomation(Context ctx, CareExecutor executor, CareAI careAI, Report report) {
        this.ctx = ctx.getApplicationContext();
        this.executor = executor;
        this.careAI = careAI;
        this.report = report;
        this.handler = new Handler(Looper.getMainLooper());
        this.comboPlayer = new ComboPlayer(this.ctx, null);
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
        worker.shutdownNow();
    }

    // ============ 自动体检 + 自动修复 ============

    private final Runnable healthTask = new Runnable() {
        @Override
        public void run() {
            if (!running) return;
            // v1.191.0：体检是纯磁盘 IO（逐模型 analyze / repair），放后台避免 ANR
            try {
                worker.execute(() -> {
                    try {
                        runHealthCheck();
                    } catch (Exception e) {
                        reportError("体检出错: " + com.digitallife.ui.UiKit.safeMsg(e));
                    }
                });
            } catch (Exception ignored) {
            }
            handler.postDelayed(this, HEALTH_INTERVAL_MS);
        }
    };

    /** 异常信息统一走气泡反馈（内部已保证切主线程） */
    private void reportError(String text) {
        if (report != null) report.onAutoReport(text);
    }

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
        // 标准 cron 周字段：0/7=周日、1=周一；Calendar 是 1=周日，需转换
        int dow = cal.get(Calendar.DAY_OF_WEEK);
        int cronDow = dow == Calendar.SUNDAY ? 0 : dow - 1;

        for (String name : all.keySet()) {
            try {
                JSONObject task = new JSONObject((String) all.get(name));
                String cron = task.optString("cronExpr", "");
                if (cron.isEmpty()) continue;
                boolean combo = isComboTask(task);
                String workflowName = task.optString("workflowName", "");
                String comboName = task.optString("comboName", "");
                if (combo ? comboName.isEmpty() : workflowName.isEmpty()) continue;
                if (!isTaskEnabled(task)) continue; // 工作台里被暂停的任务不触发
                if (!cronMatches(cron, minute, hour, day, month, cronDow)) continue;

                // 去重：同一任务同一分钟内只触发一次（检查间隔 30s 会重复命中）
                String fireKey = name + "|" + cal.get(Calendar.YEAR) + "-" + month + "-" + day + " " + hour + ":" + minute;
                if (fireKey.equals(lastFired.get(name))) continue;
                lastFired.put(name, fireKey);

                if (combo) {
                    // 连招播放留在主线程：ComboPlayer 的逐步下发依赖主线程 Handler
                    String result;
                    try {
                        int n = comboPlayer.play(task.optString("modelDir", ""), comboName);
                        result = n > 0
                                ? "✅ 已播放连招「" + comboName + "」（" + n + " 步）"
                                : "❌ 连招「" + comboName + "」" + (n == 0
                                        ? "没有可播放的步骤（动作 / 表情可能已被删除或改名）"
                                        : "播放失败（桌宠未运行或找不到连招）");
                    } catch (Exception e) {
                        result = "执行失败: " + com.digitallife.ui.UiKit.safeMsg(e);
                    }
                    finishTask(name, null, result);
                } else {
                    // v1.191.0：工作流执行是重磁盘操作（可能解压模型 / 写文件），放后台避免 ANR
                    final String wf = workflowName;
                    try {
                        worker.execute(() -> {
                            String result;
                            try {
                                JSONObject args = new JSONObject();
                                args.put("name", wf);
                                result = executor.getTools().execute("run_workflow", args);
                            } catch (Exception e) {
                                result = "执行失败: " + com.digitallife.ui.UiKit.safeMsg(e);
                            }
                            finishTask(name, wf, result);
                        });
                    } catch (Exception ignored) {
                    }
                }
            } catch (Exception ignored) {
            }
        }
    }

    /**
     * 任务执行完毕后的收尾：落盘执行记录 + 写入护理上下文 + 气泡反馈。
     *
     * <p>可能在后台线程调用（工作流路径），因此内部对共享状态的访问必须线程安全。</p>
     */
    private void finishTask(String name, String workflowName, String result) {
        // 落盘执行记录（v1.173.0）：工作台据此显示「上次运行 / 记录」
        RunLog.record(ctx, RunLog.scheduleKey(name), RunLog.looksOk(result), result);
        if (workflowName != null) {
            RunLog.record(ctx, RunLog.workflowKey(workflowName), RunLog.looksOk(result), result);
        }
        careAI.logAutomationEvent("定时任务「" + name + "」触发:\n" + result);
        if (report != null) report.onAutoReport("⏰ 定时任务「" + name + "」已触发\n" + result);
    }

    /** 是否为「定时播放连招」任务：缺省 {@code kind} 视为工作流（兼容 v1.178.0 之前的旧数据） */
    static boolean isComboTask(org.json.JSONObject task) {
        return task != null && "combo".equals(task.optString("kind", "workflow"));
    }

    /** 定时任务是否启用：缺省 {@code enabled} 字段视为启用（兼容工作台加开关前的旧数据） */
    static boolean isTaskEnabled(org.json.JSONObject task) {
        return task == null || task.optBoolean("enabled", true);
    }

    /** 简化 cron 匹配：支持 分 时 日 月 周，字段支持 *、步进、具体值、a-b 范围。dow 为 0=周日 */
    static boolean cronMatches(String cron, int minute, int hour, int day, int month, int dow) {
        if (cron == null) return false;
        String[] parts = cron.trim().split("\\s+");
        if (parts.length < 5) return false;
        try {
            if (!fieldMatch(parts[0], minute)) return false;
            if (!fieldMatch(parts[1], hour)) return false;
            if (!parts[2].equals("*") && !fieldMatch(parts[2], day)) return false;
            if (!parts[3].equals("*") && !fieldMatch(parts[3], month)) return false;
            if (!parts[4].equals("*") && !dowFieldMatch(parts[4], dow)) return false;
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /** 周字段匹配：dow 已归一化为 0=周日、1-6=周一到周六；标准 cron 中 7 也视为周日 */
    static boolean dowFieldMatch(String field, int dow) {
        if (field.startsWith("*/")) {
            int step = Integer.parseInt(field.substring(2));
            if (step <= 0) return false;
            return dow % step == 0;
        }
        if (field.equals("*")) return true;
        if (field.contains(",")) {
            for (String f : field.split(",")) {
                if (dowFieldMatch(f.trim(), dow)) return true;
            }
            return false;
        }
        if (field.contains("-")) {
            String[] range = field.split("-");
            int lo = parseDow(range[0].trim());
            int hi = parseDow(range[1].trim());
            return dow >= lo && dow <= hi;
        }
        return parseDow(field.trim()) == dow;
    }

    /** 标准 cron 周值转归一化值：7 = 周日 = 0 */
    private static int parseDow(String s) {
        int v = Integer.parseInt(s.trim());
        return v == 7 ? 0 : v;
    }

    /** 单字段匹配（分 / 时 / 日 / 月通用）：支持 *、逗号列表、步进 *&#47;n、a-b 范围、具体值 */
    static boolean fieldMatch(String field, int value) throws Exception {
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
