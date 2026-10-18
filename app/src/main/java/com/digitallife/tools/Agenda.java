package com.digitallife.tools;

import android.content.Context;
import android.content.SharedPreferences;

import com.digitallife.brain.MemoryTools;
import com.digitallife.care.CronText;

import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 「今日日程」——把两处真实数据合并成一段可读文本：**今日提醒**（{@code reminders}）与
 * **每日例行**定时任务（{@code care_schedules}）。
 *
 * <p>背景（v1.180.0）：工具市场里的 {@code get_calendar_today}「今日日程」此前是占位实现
 * （直接透传 {@link MemoryTools#getTodayEvents}，只读提醒）。本类把「每日例行」也接上——
 * 工作台里的定时任务（工作流 / 连招）用 {@link CronText} 翻成人话 + 推算下次触发，让 Agent
 * 真正答得出「今天有什么安排」。</p>
 *
 * <p><b>边界</b>：纯读、无副作用；坏 JSON / 空表 / 非法 cron 一律跳过或回退为可读提示，绝不抛。
 * 定时任务不区分「今天是否触发」而整体列为「每日例行」（它们本就是周期性的），
 * 只是用「下次触发」标注最近一次何时发生。</p>
 */
public final class Agenda {

    static final String PREFS_SCHEDULE = "care_schedules";

    private Agenda() {
    }

    /** 组装「今日日程」文本（标题 + 今日提醒 + 每日例行） */
    public static String today(Context ctx) {
        if (ctx == null) return "无法读取日程：上下文为空";
        long now = System.currentTimeMillis();
        String date = new SimpleDateFormat("MM-dd", Locale.getDefault()).format(new Date(now));
        return "📅 今日日程（" + date + "）\n"
                + MemoryTools.getTodayEvents(ctx)
                + "\n每日例行：\n" + routines(ctx, now);
    }

    /** 读取定时任务并渲染（读盘入口，容错由 {@link #formatRoutines} 兜底） */
    static String routines(Context ctx, long now) {
        SharedPreferences sp = ctx.getSharedPreferences(PREFS_SCHEDULE, Context.MODE_PRIVATE);
        List<String> lines = formatRoutines(sp.getAll(), now);
        if (lines.isEmpty()) return "（暂无）\n";
        StringBuilder sb = new StringBuilder();
        for (String l : lines) sb.append("- ").append(l).append("\n");
        return sb.toString();
    }

    /**
     * 纯逻辑：把 {@code 任务名 → JSON 串} 的定时任务表渲染成行（按名排序），便于单测。
     * 空值 / 非字符串 / 坏 JSON 一律跳过；缺省 {@code kind} 视为工作流（兼容旧数据）。
     */
    static List<String> formatRoutines(Map<String, ?> all, long now) {
        List<String> out = new ArrayList<>();
        if (all == null) return out;
        for (Map.Entry<String, ?> e : all.entrySet()) {
            Object v = e.getValue();
            if (!(v instanceof String)) continue;
            try {
                JSONObject o = new JSONObject((String) v);
                String cron = o.optString("cronExpr", "");
                boolean combo = "combo".equals(o.optString("kind", "workflow"));
                String line = e.getKey() + "（" + CronText.describe(cron) + "，下次 "
                        + nextText(cron, now) + "）→ "
                        + (combo ? "连招 " + o.optString("comboName", "?")
                                 : "工作流 " + o.optString("workflowName", "?"));
                if (!o.optBoolean("enabled", true)) line += "（已暂停）";
                out.add(line);
            } catch (Exception ignored) {
            }
        }
        Collections.sort(out);
        return out;
    }

    /** 下次触发：今天 → 「今天 HH:mm」，其他日期 → 「MM-dd HH:mm」，近期不触发 → 提示语 */
    static String nextText(String cron, long now) {
        long next = CronText.nextFireMillis(cron, now);
        if (next < 0) return "近期不触发";
        Calendar a = Calendar.getInstance();
        a.setTimeInMillis(next);
        Calendar b = Calendar.getInstance();
        b.setTimeInMillis(now);
        boolean sameDay = a.get(Calendar.YEAR) == b.get(Calendar.YEAR)
                && a.get(Calendar.DAY_OF_YEAR) == b.get(Calendar.DAY_OF_YEAR);
        String hhmm = new SimpleDateFormat("HH:mm", Locale.getDefault()).format(new Date(next));
        if (sameDay) return "今天 " + hhmm;
        return new SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(new Date(next));
    }
}
