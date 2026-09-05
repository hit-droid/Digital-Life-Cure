package com.digitallife.brain;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;

import com.digitallife.notify.AgentNotifier;
import com.digitallife.util.ApiManager;
import com.digitallife.util.ApiProfile;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 定时任务调度器（参考 OpenMinis 的一次性/周期任务）。
 * 任务持久化在 SharedPreferences，触发走 AlarmManager 非精确闹钟（免特殊权限），
 * 到点由 TaskReceiver 唤醒，后台调用对话模型执行任务并把结果发到系统通知。
 */
public class TaskScheduler {

    private static final String PREF = "scheduled_tasks";
    private static final String KEY_TASKS = "tasks";

    public static class Task {
        public String id;
        public String prompt;
        public long triggerAtMs;
        /** 0 = 一次性任务；>0 = 周期间隔毫秒 */
        public long repeatMs;
    }

    // ============ 查询 ============

    public static List<Task> list(Context ctx) {
        List<Task> out = new ArrayList<>();
        String raw = prefs(ctx).getString(KEY_TASKS, "");
        if (raw.isEmpty()) return out;
        try {
            JSONArray arr = new JSONArray(raw);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                Task t = new Task();
                t.id = o.optString("id", "");
                t.prompt = o.optString("prompt", "");
                t.triggerAtMs = o.optLong("triggerAt", 0);
                t.repeatMs = o.optLong("repeatMs", 0);
                if (!t.id.isEmpty()) out.add(t);
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    /** 供工具调用的人类可读列表 */
    public static String describe(Context ctx) {
        List<Task> tasks = list(ctx);
        if (tasks.isEmpty()) return "当前没有定时任务";
        StringBuilder sb = new StringBuilder("共 " + tasks.size() + " 个定时任务：");
        for (Task t : tasks) {
            sb.append("\n- [").append(t.id).append("] ").append(t.prompt);
            if (t.repeatMs > 0) {
                sb.append("（每 ").append(t.repeatMs / 60000).append(" 分钟重复）");
            } else {
                sb.append("（一次性）");
            }
            long mins = (t.triggerAtMs - System.currentTimeMillis()) / 60000;
            sb.append(mins > 0 ? "，约 " + mins + " 分钟后触发" : "，即将触发");
        }
        return sb.toString();
    }

    // ============ 调度 ============

    /**
     * 新建并调度任务。delayMinutes 后首次触发；repeatMinutes > 0 时按周期重复。
     * 返回人类可读结果（供工具回执）。
     */
    public static String schedule(Context ctx, String prompt, long delayMinutes, long repeatMinutes) {
        if (prompt == null || prompt.trim().isEmpty()) return "缺少任务内容 prompt";
        if (delayMinutes < 1) delayMinutes = 1;
        Task t = new Task();
        t.id = UUID.randomUUID().toString().substring(0, 6);
        t.prompt = prompt.trim();
        t.triggerAtMs = System.currentTimeMillis() + delayMinutes * 60000;
        t.repeatMs = repeatMinutes > 0 ? repeatMinutes * 60000 : 0;
        List<Task> tasks = list(ctx);
        tasks.add(t);
        persist(ctx, tasks);
        scheduleAlarm(ctx, t);
        return "已创建定时任务 [" + t.id + "]：" + t.prompt
                + (t.repeatMs > 0 ? "（每 " + repeatMinutes + " 分钟重复）" : "（一次性）")
                + "，约 " + delayMinutes + " 分钟后触发";
    }

    public static String cancel(Context ctx, String id) {
        List<Task> tasks = list(ctx);
        List<Task> keep = new ArrayList<>();
        Task found = null;
        for (Task t : tasks) {
            if (t.id.equals(id)) found = t;
            else keep.add(t);
        }
        if (found == null) return "未找到任务：" + id;
        cancelAlarm(ctx, found);
        persist(ctx, keep);
        return "已取消任务 [" + id + "]：" + found.prompt;
    }

    /** App 启动时重排所有闹钟（进程被杀/重启后 AlarmManager 注册会丢） */
    public static void rescheduleAll(Context ctx) {
        long now = System.currentTimeMillis();
        for (Task t : list(ctx)) {
            if (t.triggerAtMs < now) {
                if (t.repeatMs > 0) {
                    // 周期任务：顺延到下一个未来时点
                    long missed = (now - t.triggerAtMs) / t.repeatMs + 1;
                    t.triggerAtMs += missed * t.repeatMs;
                } else {
                    // 一次性任务已过期超过 1 天则丢弃，否则尽快补触发
                    if (now - t.triggerAtMs > 24L * 3600 * 1000) {
                        cancel(ctx, t.id);
                        continue;
                    }
                    t.triggerAtMs = now + 60000;
                }
            }
            scheduleAlarm(ctx, t);
        }
    }

    // ============ 触发执行（由 TaskReceiver 调用） ============

    /**
     * 执行指定任务。LLM 调用是异步的，完成（含失败/跳过）后回调 onDone，
     * 调用方应在此之前持有唤醒锁。
     */
    public static void onTrigger(Context context, String taskId, Runnable onDone) {
        final Context ctx = context.getApplicationContext();
        Task target = null;
        for (Task t : list(ctx)) {
            if (t.id.equals(taskId)) {
                target = t;
                break;
            }
        }
        if (target == null) {
            if (onDone != null) onDone.run();
            return;
        }
        final Task task = target;

        // 周期任务先排下一次，避免执行期间丢调度
        if (task.repeatMs > 0) {
            task.triggerAtMs = System.currentTimeMillis() + task.repeatMs;
            List<Task> tasks = list(ctx);
            for (int i = 0; i < tasks.size(); i++) {
                if (tasks.get(i).id.equals(task.id)) {
                    tasks.set(i, task);
                    break;
                }
            }
            persist(ctx, tasks);
            scheduleAlarm(ctx, task);
        } else {
            List<Task> tasks = list(ctx);
            List<Task> keep = new ArrayList<>();
            for (Task t : tasks) {
                if (!t.id.equals(task.id)) keep.add(t);
            }
            persist(ctx, keep);
        }

        ApiManager am = new ApiManager(ctx);
        ApiProfile p = am.getCurrent(ApiManager.SCOPE_CHAT);
        if (p == null || p.baseUrl == null || p.baseUrl.isEmpty()
                || p.effectiveKeys().isEmpty()) {
            AgentNotifier.notify(ctx, "定时任务未执行",
                    "没有可用的对话模型配置，任务「" + task.prompt + "」已跳过");
            if (onDone != null) onDone.run();
            return;
        }
        LLMClient llm = new LLMClient(p.baseUrl,
                am.nextKey(ApiManager.SCOPE_CHAT, p.id), p.model);
        List<LLMClient.ChatMessage> msgs = new ArrayList<>();
        msgs.add(new LLMClient.ChatMessage("user", task.prompt));
        JSONObject sys = new JSONObject();
        try {
            sys.put("system", "你是住在用户手机里的 AI 助手「小汐」。"
                    + "这是一个定时任务的自动执行，请直接完成任务并给出简洁有用的结果，"
                    + "不要反问用户。");
        } catch (Exception ignored) {
        }
        llm.chatOnce(msgs, sys, new LLMClient.Callback() {
            @Override
            public void onResult(String text, String error) {
                try {
                    if (error != null) {
                        AgentNotifier.notify(ctx, "定时任务执行失败",
                                task.prompt + "\n原因：" + error);
                        return;
                    }
                    String out = text == null ? "" : text.trim();
                    if (out.isEmpty()) out = "（任务完成，无文本结果）";
                    if (out.length() > 800) out = out.substring(0, 800) + "…";
                    AgentNotifier.notify(ctx, "定时任务完成", out);
                } finally {
                    if (onDone != null) onDone.run();
                }
            }
        });
    }

    // ============ 内部 ============

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getApplicationContext()
                .getSharedPreferences(PREF, Context.MODE_PRIVATE);
    }

    private static void persist(Context ctx, List<Task> tasks) {
        JSONArray arr = new JSONArray();
        for (Task t : tasks) {
            try {
                JSONObject o = new JSONObject();
                o.put("id", t.id);
                o.put("prompt", t.prompt);
                o.put("triggerAt", t.triggerAtMs);
                o.put("repeatMs", t.repeatMs);
                arr.put(o);
            } catch (Exception ignored) {
            }
        }
        prefs(ctx).edit().putString(KEY_TASKS, arr.toString()).apply();
    }

    private static void scheduleAlarm(Context ctx, Task t) {
        AlarmManager am = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;
        // 非精确闹钟：不需要 SCHEDULE_EXACT_ALARM 权限，允许 Doze 中唤醒
        am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, t.triggerAtMs, pendingIntent(ctx, t));
    }

    private static void cancelAlarm(Context ctx, Task t) {
        AlarmManager am = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;
        am.cancel(pendingIntent(ctx, t));
    }

    private static PendingIntent pendingIntent(Context ctx, Task t) {
        Intent i = new Intent(ctx, TaskReceiver.class);
        i.putExtra("task_id", t.id);
        return PendingIntent.getBroadcast(ctx, t.id.hashCode(), i,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }
}
