package com.digitallife.care;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Locale;

/**
 * 执行记录（自动化可观测性，v1.173.0）：把「定时任务 / 工作流」每次**真正执行**的结果落盘，
 * 供工作台显示「上次运行」并可回看最近若干次。
 *
 * <p>背景：在此之前自动化只有「管」没有「看」——{@code CareAutomation} 会按 cron 真跑工作流
 * （见 {@code CareTools.run_workflow}），但结果只写进 AI 上下文与气泡、**不落盘**；工作台能看到
 * 「下次触发」，却看不到「上次几点跑、成没成、输出了啥」。本类补上这一环。</p>
 *
 * <p>落盘位置 {@code care_runs}（key = {@code schedule|任务名} 或 {@code workflow|工作流名}），
 * 每个 key 保留最近 {@link #MAX_KEPT} 条。纯逻辑（成败判定 / 摘要 / 人话时间）与存储分开，
 * 前者可直接单测。</p>
 */
public final class RunLog {

    private static final String PREFS = "care_runs";

    /** 每个 key 保留的最近记录条数 */
    public static final int MAX_KEPT = 20;

    private RunLog() {
    }

    /** 一条执行记录：时间 + 成败 + 结果摘要 */
    public static final class Record {
        public final long at;
        public final boolean ok;
        public final String summary;

        public Record(long at, boolean ok, String summary) {
            this.at = at;
            this.ok = ok;
            this.summary = summary == null ? "" : summary;
        }
    }

    public static String scheduleKey(String taskName) {
        return "schedule|" + taskName;
    }

    public static String workflowKey(String workflowName) {
        return "workflow|" + workflowName;
    }

    // ==================== 纯逻辑 ====================

    /**
     * 从 {@code run_workflow} 的结果文本判断成败。
     *
     * <p>{@code run_workflow} 正常返回形如 {@code ▶ 执行工作流「x」:\n  步骤 1: tool → ✅ 成功…}，
     * 失败会带 {@code ❌ 失败}；参数缺失则返回「未找到 / 请指定 / 没有步骤」等提示。这里以
     * **失败标记**为准：命中任一标记即判失败，否则视为成功。</p>
     */
    public static boolean looksOk(String result) {
        if (result == null) return false;
        String[] fail = {"❌", "失败", "异常", "未找到", "没有步骤", "请指定", "请填写"};
        for (String f : fail) {
            if (result.contains(f)) return false;
        }
        return true;
    }

    /** 结果摘要：折叠换行 / 连续空白，截断到 140 字符，便于一行展示 */
    public static String summarize(String result) {
        if (result == null) return "";
        String s = result.replace('\n', ' ').replace('\r', ' ').replaceAll("\\s+", " ").trim();
        if (s.length() > 140) s = s.substring(0, 140) + "…";
        return s;
    }

    /** 把绝对时间说成人话：刚刚 / N 分钟前 / N 小时前 / N 天前 / M月d日 HH:mm */
    public static String whenText(long at, long now) {
        long d = now - at;
        if (d < 0) d = 0;
        long sec = d / 1000;
        if (sec < 60) return "刚刚";
        long min = sec / 60;
        if (min < 60) return min + " 分钟前";
        long hr = min / 60;
        if (hr < 24) return hr + " 小时前";
        long day = hr / 24;
        if (day < 7) return day + " 天前";
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(at);
        return String.format(Locale.ROOT, "%d月%d日 %02d:%02d",
                c.get(Calendar.MONTH) + 1, c.get(Calendar.DAY_OF_MONTH),
                c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE));
    }

    /** 一行摘要：{@code ✅/❌ 上次运行 <when>　<summary>}；无记录返回 {@code null} */
    public static String lastLine(Context ctx, String key, long now) {
        Record r = last(ctx, key);
        if (r == null) return null;
        return (r.ok ? "✅" : "❌") + " 上次运行 " + whenText(r.at, now)
                + (r.summary.isEmpty() ? "" : "　" + r.summary);
    }

    // ==================== 持久化 ====================

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** 追加一条记录（最新在前），并裁剪到 {@link #MAX_KEPT} 条 */
    public static void record(Context ctx, String key, boolean ok, String summary) {
        record(ctx, key, ok, summary, System.currentTimeMillis());
    }

    public static void record(Context ctx, String key, boolean ok, String summary, long at) {
        if (key == null || key.isEmpty()) return;
        List<Record> list = read(ctx, key);
        list.add(0, new Record(at, ok, summarize(summary)));
        while (list.size() > MAX_KEPT) list.remove(list.size() - 1);
        prefs(ctx).edit().putString(key, toJson(list).toString()).apply();
    }

    /** 最近一条；无则 {@code null} */
    public static Record last(Context ctx, String key) {
        List<Record> l = read(ctx, key);
        return l.isEmpty() ? null : l.get(0);
    }

    /** 最近记录（最新在前） */
    public static List<Record> recent(Context ctx, String key) {
        return read(ctx, key);
    }

    public static void clear(Context ctx, String key) {
        if (key == null || key.isEmpty()) return;
        prefs(ctx).edit().remove(key).apply();
    }

    private static List<Record> read(Context ctx, String key) {
        List<Record> out = new ArrayList<>();
        if (key == null || key.isEmpty()) return out;
        String raw = prefs(ctx).getString(key, null);
        if (raw == null || raw.isEmpty()) return out;
        try {
            return fromJson(new JSONArray(raw));
        } catch (Exception e) {
            return out;
        }
    }

    static JSONArray toJson(List<Record> list) {
        JSONArray arr = new JSONArray();
        if (list == null) return arr;
        for (Record r : list) {
            JSONObject o = new JSONObject();
            try {
                o.put("at", r.at);
                o.put("ok", r.ok);
                o.put("summary", r.summary);
            } catch (Exception ignored) {
            }
            arr.put(o);
        }
        return arr;
    }

    static List<Record> fromJson(JSONArray arr) {
        List<Record> out = new ArrayList<>();
        if (arr == null) return out;
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o == null) continue;
            out.add(new Record(o.optLong("at", 0), o.optBoolean("ok", false),
                    o.optString("summary", "")));
        }
        return out;
    }
}
